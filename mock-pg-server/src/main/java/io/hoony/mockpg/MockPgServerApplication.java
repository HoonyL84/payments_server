package io.hoony.mockpg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MockPgServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockPgServerApplication.class, args);
    }
}