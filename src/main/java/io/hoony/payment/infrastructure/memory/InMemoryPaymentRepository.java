package io.hoony.payment.infrastructure.memory;

import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.domain.payment.Payment;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryPaymentRepository implements PaymentRepository {

    private final ConcurrentMap<UUID, Payment> payments = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> paymentIdsByOrder = new ConcurrentHashMap<>();

    @Override
    public void save(Payment payment) {
        payments.put(payment.id(), payment);
        paymentIdsByOrder.put(orderKey(payment.merchantId(), payment.orderId()), payment.id());
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return Optional.ofNullable(payments.get(id));
    }

    @Override
    public Optional<Payment> findByMerchantIdAndOrderId(String merchantId, String orderId) {
        UUID paymentId = paymentIdsByOrder.get(orderKey(merchantId, orderId));
        if (paymentId == null) {
            return Optional.empty();
        }
        return findById(paymentId);
    }

    @Override
    public long count() {
        return payments.size();
    }

    private static String orderKey(String merchantId, String orderId) {
        return merchantId + "|" + orderId;
    }
}