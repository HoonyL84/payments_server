package io.hoony.payment.infrastructure.persistence.entity;

import io.hoony.payment.domain.merchant.MerchantContract;
import io.hoony.payment.domain.merchant.MerchantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "merchants")
public class MerchantEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MerchantStatus status;

    @Column(name = "pg_provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "routing_key", nullable = false, length = 128)
    private String routingKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MerchantEntity() {
    }

    public MerchantContract toDomain() {
        return new MerchantContract(id, status, provider, routingKey);
    }
}
