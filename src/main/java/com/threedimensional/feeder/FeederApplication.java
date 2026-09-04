package com.threedimensional.feeder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class FeederApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeederApplication.class, args);
    }

}
