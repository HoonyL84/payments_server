package io.hoony.payment.infrastructure.pg;

import io.hoony.payment.application.port.out.PaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("!test & !integration")
@Configuration
public class ProviderPaymentGatewayConfiguration {

    @Bean("providerPaymentGateway")
    @ConditionalOnProperty(name = "payments.pg.mode", havingValue = "fake", matchIfMissing = true)
    PaymentGateway fakeProviderPaymentGateway(FakePaymentGateway gateway) {
        return gateway;
    }

    @Bean("providerPaymentGateway")
    @ConditionalOnProperty(name = "payments.pg.mode", havingValue = "http")
    PaymentGateway httpProviderPaymentGateway(HttpPaymentGateway gateway) {
        return gateway;
    }
}