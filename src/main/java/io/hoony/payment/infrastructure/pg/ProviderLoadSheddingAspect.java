package io.hoony.payment.infrastructure.pg;

import io.hoony.payment.application.admission.ProviderCapacityGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Profile("!test & !integration")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@Aspect
@Component
public class ProviderLoadSheddingAspect {
    private final ProviderCapacityGuard capacity;
    private final ResilientPaymentGateway gateway;

    public ProviderLoadSheddingAspect(
            ProviderCapacityGuard capacity,
            ResilientPaymentGateway gateway
    ) {
        this.capacity = capacity;
        this.gateway = gateway;
    }

    @Around("execution(* io.hoony.payment.application.approval.ApprovePaymentService.approve(..))")
    public Object approve(ProceedingJoinPoint point) {
        return execute(ProviderOperation.APPROVE, point);
    }

    @Around("execution(* io.hoony.payment.application.cancellation.CancelPaymentService.cancel(..))")
    public Object cancel(ProceedingJoinPoint point) {
        return execute(ProviderOperation.CANCEL, point);
    }

    @Around("execution(* io.hoony.payment.application.confirmation.ConfirmPaymentService.confirm(..))")
    public Object confirmApprove(ProceedingJoinPoint point) {
        return execute(ProviderOperation.CONFIRM_APPROVE, point);
    }

    @Around("execution(* io.hoony.payment.application.cancellation.ConfirmCancellationService.confirm(..))")
    public Object confirmCancel(ProceedingJoinPoint point) {
        return execute(ProviderOperation.CONFIRM_CANCEL, point);
    }

    private Object execute(ProviderOperation operation, ProceedingJoinPoint point) {
        gateway.ensureAvailable(operation);
        return capacity.execute(operation, () -> proceed(point));
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
