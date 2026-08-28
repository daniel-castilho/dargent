package io.dargent.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Composition root (design.md §3.2): wires the business modules into one Boot application.
 * No domain logic lives here — ever (AGENTS.md §2).
 */
@SpringBootApplication
public class DargentApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DargentApiApplication.class, args);
    }
}
