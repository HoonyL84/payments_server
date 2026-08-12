package io.hoony.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.domain.outbox.OutboxEvent;
import io.hoony.payment.domain.outbox.OutboxEventType;
import io.hoony.payment.domain.outbox.OutboxStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("integration")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.data.redis.repositories.enabled=false"
})
class OutboxClaimPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("payments")
            .withUsername("payments")
            .withPassword("payments");

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void concurrentWorkersClaimDisjointBatches() throws Exception {
        for (int index = 0; index < 40; index++) {
            outboxEvents.save(pending(index));
        }

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var workerOne = executor.submit(() -> claimAfter(start, "worker-one", 20));
            var workerTwo = executor.submit(() -> claimAfter(start, "worker-two", 20));
            start.countDown();

            List<OutboxEvent> firstOwned = new ArrayList<>(
                    workerOne.get(10, TimeUnit.SECONDS)
            );
            List<OutboxEvent> secondOwned = new ArrayList<>(
                    workerTwo.get(10, TimeUnit.SECONDS)
            );
            assertThat(firstOwned).hasSizeLessThanOrEqualTo(20);
            assertThat(secondOwned).hasSizeLessThanOrEqualTo(20);

            Map<UUID, String> claimOwners = new HashMap<>();
            recordClaims(firstOwned, "worker-one", claimOwners);
            recordClaims(secondOwned, "worker-two", claimOwners);

            List<OutboxEvent> remaining;
            do {
                remaining = outboxEvents.claimPending(
                        "recovery-worker", Duration.ofSeconds(30), 20);
                recordClaims(remaining, "recovery-worker", claimOwners);
            } while (!remaining.isEmpty());

            assertThat(claimOwners).hasSize(40);

            claimOwners.forEach((eventId, ownerId) -> assertThat(outboxEvents.markPublished(
                    eventId, ownerId, NOW.plusSeconds(1))).isTrue());
            assertThat(outboxEvents.findAll())
                    .allSatisfy(event -> {
                        assertThat(event.status()).isEqualTo(OutboxStatus.PUBLISHED);
                        assertThat(event.publishAttempts()).isEqualTo(1);
                    });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedButPreviousOwnerCannotComplete() {
        OutboxEvent event = pending(1);
        outboxEvents.save(event);

        assertThat(outboxEvents.claimPending(
                "worker-one", Duration.ofSeconds(30), 1)).hasSize(1);
        assertThat(outboxEvents.claimPending(
                "worker-two", Duration.ofSeconds(30), 1)).isEmpty();
        assertThat(outboxEvents.findPendingBefore(NOW.plusSeconds(1), 10)).isEmpty();

        jdbc.update("""
                UPDATE outbox_events
                SET claimed_until = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND
                WHERE id = ?
                """, event.id().toString());
        assertThat(outboxEvents.findPendingBefore(NOW.plusSeconds(1), 10)).hasSize(1);
        assertThat(outboxEvents.claimPending(
                "worker-two", Duration.ofSeconds(30), 1)).hasSize(1);

        assertThat(outboxEvents.markPublished(
                event.id(), "worker-one", NOW.plusSeconds(32))).isFalse();
        assertThat(outboxEvents.markPublished(
                event.id(), "worker-two", NOW.plusSeconds(32))).isTrue();
    }

    private List<OutboxEvent> claimAfter(
            CountDownLatch start,
            String owner,
            int limit
    ) throws InterruptedException {
        start.await();
        return outboxEvents.claimPending(owner, Duration.ofSeconds(30), limit);
    }

    private void recordClaims(
            List<OutboxEvent> events,
            String ownerId,
            Map<UUID, String> claimOwners
    ) {
        events.forEach(event -> assertThat(claimOwners.put(event.id(), ownerId)).isNull());
    }

    private OutboxEvent pending(int index) {
        return OutboxEvent.pending(
                UUID.nameUUIDFromBytes(
                        ("outbox-" + index).getBytes(StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes(
                        ("payment-" + index).getBytes(StandardCharsets.UTF_8)),
                OutboxEventType.PAYMENT_APPROVED,
                "{}",
                NOW.minusSeconds(40 - index)
        );
    }
}
