package io.dargent.payments.persistence;

import io.dargent.payments.domain.port.out.PaymentRepository;

/**
 * Runs the shared {@link PaymentRepositoryContractSuite} against the in-memory
 * fake — proving the fake, and the suite itself, before the JPA adapter inherits it.
 */
class InMemoryPaymentRepositoryContractTest extends PaymentRepositoryContractSuite {

    private final InMemoryPaymentRepository fake = new InMemoryPaymentRepository();

    @Override
    protected PaymentRepository repository() {
        return fake;
    }
}