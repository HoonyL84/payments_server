package io.hoony.payment.application.outbox;

import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.application.port.out.OutboxPublisher;
import io.hoony.payment.domain.outbox.OutboxEvent;
import io.hoony.payment.domain.outbox.OutboxEventType;
import io.hoony.payment.domain.outbox.OutboxStatus;
import io.hoony.payment.infrastructure.memory.InMemoryOutboxEventRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayServiceTest {

    @Test
    void relayPending_발행성공후상태갱신이실패하면_같은eventId를재발행한다() {
        PublishedSaveFailsOnceRepository repository = new PublishedSaveFailsOnceRepository();
        RecordingPublisher publisher = new RecordingPublisher();
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC);
        OutboxRelayService service = new OutboxRelayService(repository, publisher, clock);
        UUID eventId = UUID.randomUUID();
        repository.save(OutboxEvent.pending(
                eventId,
                UUID.randomUUID(),
                OutboxEventType.PAYMENT_APPROVED,
                "{}",
                Instant.parse("2026-07-10T00:00:00Z")
        ));

        assertThat(service.relayPending(10)).isZero();
        assertThat(service.relayPending(10)).isEqualTo(1);

        assertThat(publisher.publishedIds).containsExactly(eventId, eventId);
        assertThat(repository.findAll()).singleElement().satisfies(event -> {
            assertThat(event.status()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(event.publishAttempts()).isEqualTo(2);
            assertThat(event.publishedAt()).isEqualTo(Instant.parse("2026-07-11T00:00:00Z"));
            assertThat(event.publishLag()).contains(java.time.Duration.ofDays(1));
        });
    }

    private static final class RecordingPublisher implements OutboxPublisher {

        private final List<UUID> publishedIds = new ArrayList<>();

        @Override
        public void publish(OutboxEvent event) {
            publishedIds.add(event.id());
        }
    }

    private static final class PublishedSaveFailsOnceRepository
            implements OutboxEventRepository {

        private final InMemoryOutboxEventRepository delegate =
                new InMemoryOutboxEventRepository();
        private final AtomicBoolean failPublishedSave = new AtomicBoolean(true);

        @Override
        public void save(OutboxEvent event) {
            if (event.status() == OutboxStatus.PUBLISHED
                    && failPublishedSave.getAndSet(false)) {
                throw new IllegalStateException("status update failed");
            }
            delegate.save(event);
        }

        @Override
        public List<OutboxEvent> findPending(int limit) {
            return delegate.findPending(limit);
        }

        @Override
        public List<OutboxEvent> findAll() {
            return delegate.findAll();
        }
    }
}
