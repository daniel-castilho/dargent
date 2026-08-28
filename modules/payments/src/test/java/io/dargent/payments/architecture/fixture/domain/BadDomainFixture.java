package io.dargent.payments.architecture.fixture.domain;

import org.springframework.stereotype.Component;

/**
 * Test-only fixture that DELIBERATELY violates domain purity (Spring annotation inside a domain
 * package) to prove the ArchUnit gate fires — see PaymentsArchitectureTest. Never referenced by
 * production code; lives in test sources only.
 */
@Component
class BadDomainFixture {
}
