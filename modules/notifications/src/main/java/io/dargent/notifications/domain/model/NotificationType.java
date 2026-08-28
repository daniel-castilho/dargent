package io.dargent.notifications.domain.model;

/**
 * Seed enum for notifications module — M0 placeholder.
 * Real notification types arrive with M2.
 */
enum NotificationType {
    PAYMENT_CREATED,
    PAYMENT_CONFIRMED,
    PAYMENT_EXPIRED,
    PAYMENT_FAILED,
    REFUND_CREATED
}