package io.hoony.payment.infrastructure.persistence.entity;

import io.hoony.payment.domain.outbox.OutboxEvent;
import io.hoony.payment.domain.outbox.OutboxEventType;
import io.hoony.payment.domain.outbox.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;

    @Column(name = "aggregate_type", nullable = false, length = 32)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, columnDefinition = "char(36)")
    private String aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private OutboxEventType eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "json")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEventEntity() {
    }

    public OutboxEventEntity(OutboxEvent event) {
        this.id = event.id().toString();
        this.aggregateType = "PAYMENT";
        this.aggregateId = event.aggregateId().toString();
        this.eventType = event.type();
        this.createdAt = event.createdAt();
        update(event);
    }

    public void update(OutboxEvent event) {
        this.payload = event.payload();
        this.status = event.status();
        this.publishAttempts = event.publishAttempts();
        this.publishedAt = event.publishedAt();
    }

    public OutboxEvent toDomain() {
        return new OutboxEvent(
                UUID.fromString(id),
                UUID.fromString(aggregateId),
                eventType,
                payload,
                status,
                publishAttempts,
                createdAt,
                publishedAt
        );
    }
}
