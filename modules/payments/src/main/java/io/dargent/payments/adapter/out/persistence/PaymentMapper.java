package io.dargent.payments.adapter.out.persistence;

import io.dargent.payments.domain.model.EndToEndId;
import io.dargent.payments.domain.model.FeeBreakdown;
import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;
import io.dargent.shared.money.Money;

/**
 * Maps between the framework-free {@link Payment} aggregate and the JPA
 * {@link PaymentEntity} (decision D14 — separate persistence model). All
 * persistence-shaped types stay in the adapter; the domain never sees them.
 */
public final class PaymentMapper {

    private static final String BRL = "BRL";

    private PaymentMapper() {
    }

    public static PaymentEntity toEntity(Payment payment) {
        PaymentEntity entity = new PaymentEntity();
        entity.setId(java.util.UUID.randomUUID());
        entity.setTxid(payment.txid().value());
        entity.setMerchantId(payment.merchantId());
        entity.setDescription(payment.description());
        entity.setAmountCents(payment.amount().cents());
        entity.setStatus(payment.status().name());
        entity.setVersion(payment.version());
        entity.setExpiresAt(payment.expiresAt());
        if (payment.endToEndId() != null) {
            entity.setEndToEndId(payment.endToEndId().value());
        }
        if (payment.fee() != null) {
            entity.setFeeCents(payment.fee().cents());
        }
        if (payment.net() != null) {
            entity.setNetCents(payment.net().cents());
        }
        entity.setLateConfirmation(payment.lateConfirmation());
        entity.setRefundedCents(payment.refunded().cents());
        entity.setCreatedAt(payment.createdAt());
        entity.setConfirmedAt(payment.confirmedAt());
        return entity;
    }

    public static PaymentEntity copyFieldsInto(Payment payment, PaymentEntity entity) {
        entity.setTxid(payment.txid().value());
        entity.setMerchantId(payment.merchantId());
        entity.setDescription(payment.description());
        entity.setAmountCents(payment.amount().cents());
        entity.setStatus(payment.status().name());
        entity.setVersion(payment.version());
        entity.setExpiresAt(payment.expiresAt());
        entity.setEndToEndId(payment.endToEndId() == null ? null : payment.endToEndId().value());
        entity.setFeeCents(payment.fee() == null ? null : payment.fee().cents());
        entity.setNetCents(payment.net() == null ? null : payment.net().cents());
        entity.setLateConfirmation(payment.lateConfirmation());
        entity.setRefundedCents(payment.refunded().cents());
        entity.setCreatedAt(payment.createdAt());
        entity.setConfirmedAt(payment.confirmedAt());
        return entity;
    }

    public static Payment toDomain(PaymentEntity entity) {
        Money amount = Money.of(entity.getAmountCents(), BRL);
        EndToEndId endToEndId = entity.getEndToEndId() == null ? null : new EndToEndId(entity.getEndToEndId());
        Money fee = entity.getFeeCents() == null ? null : Money.of(entity.getFeeCents(), BRL);
        Money net = entity.getNetCents() == null ? null : Money.of(entity.getNetCents(), BRL);
        return Payment.restore(
                new Txid(entity.getTxid()),
                entity.getMerchantId(),
                amount,
                entity.getDescription(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                PaymentStatus.valueOf(entity.getStatus()),
                entity.getVersion(),
                endToEndId,
                fee,
                net,
                entity.isLateConfirmation(),
                entity.getConfirmedAt(),
                entity.getRefundedCents());
    }
}