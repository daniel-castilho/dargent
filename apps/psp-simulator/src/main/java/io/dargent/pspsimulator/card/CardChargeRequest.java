package io.dargent.pspsimulator.card;

/**
 * Create request for the instant card profile (M5 S1, D2 adjudication). The {@code token} is the
 * card credential the platform passes through from its {@code cardToken} surface field — the
 * simulator stores it only for the charge's identity; no card data leaves the profile.
 */
public record CardChargeRequest(String txid, long amount, String callbackUrl, String token) {}
