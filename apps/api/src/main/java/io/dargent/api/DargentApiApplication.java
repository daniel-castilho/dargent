package io.dargent.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

/**
 * Composition root (design.md §3.2): wires the business modules into one Boot application.
 * No domain logic lives here — ever (AGENTS.md §2).
 * Entities live in the payments persistence adapter (outside the {@code io.dargent.api} scan);
 * {@code @EntityScan} discovers them without widening the component scan.
 */
@SpringBootApplication
@EntityScan("io.dargent.payments.adapter.out.persistence")
public class DargentApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DargentApiApplication.class, args);
    }
}
