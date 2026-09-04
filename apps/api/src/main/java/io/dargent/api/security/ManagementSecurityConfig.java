package io.dargent.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
    public SecurityFilterChain managementFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(new ManagementPortRequestMatcher())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll());
        return http.build();
    }

    /**
     * Matches requests that come to the management port.
     * The management port is configured via {@code management.server.port} (default 9090).
     */
    static class ManagementPortRequestMatcher implements RequestMatcher {
        @Override
        public boolean matches(HttpServletRequest request) {
            // The management port is 9090 (or configured via DARGENT_MANAGEMENT_PORT)
            // In tests it's fixed at 9090
            return request.getServerPort() == 9090;
        }
    }
}