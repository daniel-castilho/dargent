package io.dargent.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Single source of truth for route authorization (AGENTS.md §4.1, design.md §8.1).
 * Stateless JWT-free: API key via {@link ApiKeyAuthenticationFilter}.
 * <ul>
 *   <li>{@code /v1/**} — authenticated (API key required)</li>
 *   <li>{@code /webhooks/psp} — open (HMAC validated in a future filter, E4)</li>
 *   <li>{@code /actuator/health, /actuator/info} — open</li>
 *   <li>everything else — 401</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    private final ApiKeyAuthenticationFilter apiKeyFilter;

    public SecurityConfig(ApiKeyAuthenticationFilter apiKeyFilter) {
        this.apiKeyFilter = apiKeyFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/webhooks/psp").permitAll()
                        .requestMatchers("/v1/**").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}