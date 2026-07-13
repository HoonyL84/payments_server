package io.hoony.payment.infrastructure.persistence.repository;

import io.hoony.payment.domain.outbox.OutboxStatus;
import io.hoony.payment.infrastructure.persistence.entity.OutboxEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface JpaOutboxEventEntityRepository
        extends JpaRepository<OutboxEventEntity, String> {

    List<OutboxEventEntity> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    @Query(value = """
            SELECT *
            FROM outbox_events
            WHERE status = 'PENDING'
              AND created_at < :createdBefore
              AND (claimed_until IS NULL OR claimed_until <= CURRENT_TIMESTAMP(6))
            ORDER BY created_at ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<OutboxEventEntity> findClaimablePendingBefore(
            @Param("createdBefore") Instant createdBefore,
            @Param("limit") int limit
    );

    @Query(value = """
            SELECT *
            FROM outbox_events
            WHERE status = 'PENDING'
              AND (claimed_until IS NULL OR claimed_until <= CURRENT_TIMESTAMP(6))
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEventEntity> lockClaimable(@Param("limit") int limit);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_events
            SET claim_owner = :ownerId,
                claimed_until = TIMESTAMPADD(
                    MICROSECOND,
                    :leaseMicroseconds,
                    CURRENT_TIMESTAMP(6)
                )
            WHERE id = :eventId
              AND status = 'PENDING'
            """, nativeQuery = true)
    int claim(
            @Param("eventId") String eventId,
            @Param("ownerId") String ownerId,
            @Param("leaseMicroseconds") long leaseMicroseconds
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_events
            SET status = 'PUBLISHED',
                publish_attempts = publish_attempts + 1,
                published_at = :publishedAt,
                claim_owner = NULL,
                claimed_until = NULL
            WHERE id = :eventId
              AND status = 'PENDING'
              AND claim_owner = :ownerId
              AND claimed_until > CURRENT_TIMESTAMP(6)
            """, nativeQuery = true)
    int markPublished(
            @Param("eventId") String eventId,
            @Param("ownerId") String ownerId,
            @Param("publishedAt") Instant publishedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_events
            SET publish_attempts = publish_attempts + 1,
                claim_owner = NULL,
                claimed_until = NULL
            WHERE id = :eventId
              AND status = 'PENDING'
              AND claim_owner = :ownerId
            """, nativeQuery = true)
    int releaseClaim(
            @Param("eventId") String eventId,
            @Param("ownerId") String ownerId
    );
}
