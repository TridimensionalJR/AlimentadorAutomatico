package com.threedimensional.feeder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Entry point. {@code @SpringBootApplication} also component-scans this package and everything
 * below it, so new layers (service, controller, dto) must live under
 * {@code com.threedimensional.feeder} to be picked up.
 * <p>
 * {@code @EnableJpaAuditing} is what backs {@code @CreatedDate} and {@code @LastModifiedDate} on
 * every entity. Without it the auditing annotations are inert and those columns stay null, which
 * then fails the {@code nullable = false} declared on {@code created_at}.
 */
@SpringBootApplication
@EnableJpaAuditing
public class FeederApplication {

    static void main(String[] args) {
        SpringApplication.run(FeederApplication.class, args);
    }

}
