package io.hoony.payment.observability;

import io.hoony.payment.application.approval.ApprovePaymentResult;
import io.hoony.payment.application.cancellation.CancelPaymentResult;
import io.hoony.payment.application.cancellation.ConfirmCancellationResult;
import io.hoony.payment.application.confirmation.ConfirmPaymentResult;
import io.hoony.payment.domain.idempotency.IdempotencyConflictException;
import io.hoony.payment.domain.outbox.OutboxEvent;
import io.hoony.payment.domain.payment.InvalidStateTransitionException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class PaymentObservabilityAspect {
    private final PaymentMetrics metrics;

    public PaymentObservabilityAspect(PaymentMetrics metrics) {
        this.metrics = metrics;
    }

    @Around("execution(* io.hoony.payment.application.approval.ApprovePaymentService.approve(..))")
    public Object approval(ProceedingJoinPoint point) throws Throwable {
        try {
            ApprovePaymentResult result = (ApprovePaymentResult) point.proceed();
            metrics.result("approval", result.state().name().toLowerCase());
            if (result.reused()) metrics.idempotency("approve", "reused");
            return result;
        } catch (IdempotencyConflictException exception) {
            metrics.idempotency("approve", "conflict");
            throw exception;
        }
    }

    @Around("execution(* io.hoony.payment.application.cancellation.CancelPaymentService.cancel(..))")
    public Object cancellation(ProceedingJoinPoint point) throws Throwable {
        try {
            CancelPaymentResult result = (CancelPaymentResult) point.proceed();
            metrics.result("cancellation", result.state().name().toLowerCase());
            if (result.reused()) metrics.idempotency("cancel", "reused");
            return result;
        } catch (IdempotencyConflictException exception) {
            metrics.idempotency("cancel", "conflict");
            throw exception;
        }
    }

    @Around("execution(* io.hoony.payment.application.confirmation.ConfirmPaymentService.confirm(..))")
    public Object confirmApproval(ProceedingJoinPoint point) throws Throwable {
        ConfirmPaymentResult result = (ConfirmPaymentResult) point.proceed();
        metrics.result("approval_confirmation", result.state().name().toLowerCase());
        return result;
    }

    @Around("execution(* io.hoony.payment.application.cancellation.ConfirmCancellationService.confirm(..))")
    public Object confirmCancellation(ProceedingJoinPoint point) throws Throwable {
        ConfirmCancellationResult result = (ConfirmCancellationResult) point.proceed();
        metrics.result("cancellation_confirmation", result.state().name().toLowerCase());
        return result;
    }

    @Around("execution(* io.hoony.payment.infrastructure.pg.FakePaymentGateway.approve(..)) || " +
            "execution(* io.hoony.payment.infrastructure.pg.FakePaymentGateway.confirmApprove(..)) || " +
            "execution(* io.hoony.payment.infrastructure.pg.FakePaymentGateway.cancel(..)) || " +
            "execution(* io.hoony.payment.infrastructure.pg.FakePaymentGateway.confirmCancel(..))")
    public Object pg(ProceedingJoinPoint point) {
        return metrics.time("payments.pg.latency", point.getSignature().getName(), () -> proceed(point));
    }

    @Around("execution(@org.springframework.transaction.annotation.Transactional * io.hoony.payment.application..*(..))")
    public Object transaction(ProceedingJoinPoint point) {
        return metrics.time("payments.db.transaction.duration", point.getSignature().toShortString(), () -> proceed(point));
    }

    @Around("execution(* io.hoony.payment.infrastructure.persistence.adapter.*.findByIdForUpdate(..)) || " +
            "execution(* io.hoony.payment.infrastructure.persistence.adapter.*.claimForConfirmation(..))")
    public Object lock(ProceedingJoinPoint point) {
        return metrics.time("payments.db.lock.wait", point.getSignature().getName(), () -> proceed(point));
    }

    @Around("execution(* io.hoony.payment.infrastructure.outbox.*.publish(..)) && args(event)")
    public Object outbox(ProceedingJoinPoint point, OutboxEvent event) {
        try {
            Object result = proceed(point);
            metrics.outboxPublished(event.createdAt());
            return result;
        } catch (RuntimeException exception) {
            metrics.outboxFailed();
            throw exception;
        }
    }

    @Around("execution(* io.hoony.payment.domain..*.apply(..))")
    public Object transition(ProceedingJoinPoint point) {
        try {
            return proceed(point);
        } catch (InvalidStateTransitionException exception) {
            metrics.invalidTransition();
            throw exception;
        }
    }

    @org.aspectj.lang.annotation.AfterThrowing(
            pointcut = "execution(* io.hoony.payment.application..*Service.*(..))",
            throwing = "exception"
    )
    public void invalidTransition(InvalidStateTransitionException exception) {
        metrics.invalidTransition();
    }

    private Object proceed(ProceedingJoinPoint point) {
        try {
            return point.proceed();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException(throwable);
        }
    }
}
