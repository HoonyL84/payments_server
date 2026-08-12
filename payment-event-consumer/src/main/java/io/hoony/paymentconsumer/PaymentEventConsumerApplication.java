package io.hoony.paymentconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentEventConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentEventConsumerApplication.class, args);
    }
}