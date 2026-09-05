package io.dargent.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Security configuration for the isolated management port (E11).
 * Permits actuator endpoints (health, info, prometheus) without authentication.
 * This is separate from the main port {@link SecurityConfig} which denies all actuator access.
 */
@Configuration
public class ManagementSecurityConfig {

    private final int managementPort;

    public ManagementSecurityConfig(Environment env) {
        // Mirrors application.yaml: management.server.port=${DARGENT_MANAGEMENT_PORT:9090}.
        // The matcher must follow the configured port, not a hardcoded 9090 — otherwise a
        // custom DARGENT_MANAGEMENT_PORT silently denies actuator (broken health checks).
        this.managementPort = env.getProperty("management.server.port", Integer.class, 9090);
    }

    @Bean
    public SecurityFilterChain managementFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(new ManagementPortRequestMatcher(managementPort))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll());
        return http.build();
    }

    /**
     * Matches requests that arrive on the configured management port
     * ({@code management.server.port}, default 9090).
     */
    static final class ManagementPortRequestMatcher implements RequestMatcher {
        private final int managementPort;

        ManagementPortRequestMatcher(int managementPort) {
            this.managementPort = managementPort;
        }

        @Override
        public boolean matches(HttpServletRequest request) {
            return request.getServerPort() == managementPort;
        }
    }
}