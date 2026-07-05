package io.hoony.payment.domain.money;

import io.hoony.payment.domain.common.DomainException;

import java.util.Objects;

/**
 * Immutable money value represented in integer minor units.
 */
public record Money(long minorUnits, String currency) {

    private static final String DEFAULT_CURRENCY = "KRW";

    /**
     * Validates money representation.
     */
    public Money {
        if (minorUnits < 0) {
            throw new DomainException("Money minor units must be zero or positive.");
        }
        if (currency == null || currency.isBlank()) {
            throw new DomainException("Money currency is required.");
        }
        currency = currency.trim().toUpperCase();
    }

    /**
     * Creates a KRW money value.
     */
    public static Money krw(long minorUnits) {
        return new Money(minorUnits, DEFAULT_CURRENCY);
    }

    /**
     * Creates a positive KRW money value.
     */
    public static Money positiveKrw(long minorUnits) {
        Money money = krw(minorUnits);
        money.requirePositive();
        return money;
    }

    /**
     * Returns whether this money value is zero.
     */
    public boolean isZero() {
        return minorUnits == 0;
    }

    /**
     * Returns whether this money value is greater than zero.
     */
    public boolean isPositive() {
        return minorUnits > 0;
    }

    /**
     * Adds money with the same currency.
     */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    /**
     * Subtracts money with the same currency.
     */
    public Money minus(Money other) {
        requireSameCurrency(other);
        if (minorUnits < other.minorUnits) {
            throw new DomainException("Money cannot become negative.");
        }
        return new Money(minorUnits - other.minorUnits, currency);
    }

    /**
     * Returns whether this money is greater than another money value.
     */
    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return minorUnits > other.minorUnits;
    }

    /**
     * Requires this money value to be positive.
     */
    public void requirePositive() {
        if (!isPositive()) {
            throw new DomainException("Money must be positive.");
        }
    }

    /**
     * Requires both money values to use the same currency.
     */
    public void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new DomainException("Money currency mismatch.");
        }
    }
}