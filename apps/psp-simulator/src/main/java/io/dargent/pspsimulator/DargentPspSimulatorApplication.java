package io.dargent.pspsimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The outside world, honestly simulated: merchant-side PSP (cobs, BR Codes, signed webhooks)
 * plus the payer bank that "pays" a QR — with chaos knobs (design.md §12).
 * Shares no code with the API; no io.dargent.* imports beyond its own package (AGENTS.md §2).
 */
@SpringBootApplication
public class DargentPspSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(DargentPspSimulatorApplication.class, args);
    }
}
