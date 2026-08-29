package io.dargent.pspsimulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The receiver profile the charges advertise — the raw PIX material the platform's BR Code
 * composer (E3) consumes as {@code pixKey}/{@code receiverName}/{@code receiverCity}
 * (E2 spec §5.1). Bound to {@code dargent.psp.profile.*}.
 */
@ConfigurationProperties("dargent.psp.profile")
public record PspProfile(String pixKey, String receiverName, String receiverCity) {
}