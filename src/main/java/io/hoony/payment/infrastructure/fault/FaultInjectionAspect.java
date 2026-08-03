package io.hoony.payment.infrastructure.fault;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("k6")
@Aspect
@Component
public class FaultInjectionAspect {
    private final FaultInjectionState state;

    public FaultInjectionAspect(FaultInjectionState state) {
        this.state = state;
    }

    @Around("execution(* io.hoony.payment.infrastructure.pg.FakePaymentGateway.approve(..)) || " +
            "execution(* io.hoony.payment.infrastructure.pg.FakePaymentGateway.cancel(..))")
    public Object pgCall(ProceedingJoinPoint point) throws Throwable {
        state.failIfArmed(FailurePoint.BEFORE_PG);
        Object result = point.proceed();
        state.failIfArmed(FailurePoint.AFTER_PG_BEFORE_DB);
        return result;
    }

    @Around("execution(* io.hoony.payment.infrastructure.pg.FakePaymentGateway.confirm*(..))")
    public Object confirmationCall(ProceedingJoinPoint point) throws Throwable {
        state.failIfArmed(FailurePoint.CONFIRMING_WORKER_STOP);
        return point.proceed();
    }

    @Around("execution(* io.hoony.payment.application.outbox.OutboxRelayService.relayPending(..))")
    public Object beforeRelay(ProceedingJoinPoint point) throws Throwable {
        state.failIfArmed(FailurePoint.AFTER_DB_BEFORE_OUTBOX_RELAY);
        return point.proceed();
    }

    @Around("execution(* io.hoony.payment.infrastructure.outbox.*.publish(..))")
    public Object afterPublish(ProceedingJoinPoint point) throws Throwable {
        Object result = point.proceed();
        state.failIfArmed(FailurePoint.AFTER_OUTBOX_PUBLISH_BEFORE_STATUS_UPDATE);
        return result;
    }
}
