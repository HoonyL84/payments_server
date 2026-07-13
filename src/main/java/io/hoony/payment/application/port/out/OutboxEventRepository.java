package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.outbox.OutboxEvent;

import java.util.List;

public interface OutboxEventRepository {

    void save(OutboxEvent event);

    List<OutboxEvent> findPending(int limit);

    List<OutboxEvent> findAll();
}
