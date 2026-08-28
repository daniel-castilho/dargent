package io.dargent.payments.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Module boundaries for payments (design.md §3.3, AGENTS.md §2) — including the M0 acceptance
 * proof that the gate actually fires on a deliberate violation.
 */
class PaymentsArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.dargent.payments");

    @Test
    void domain_layer_is_framework_free() {
        domainPurityRule().check(PRODUCTION);
    }

    @Test
    void payments_never_reaches_into_sibling_modules() {
        noClasses()
                .that().resideInAPackage("io.dargent.payments..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.dargent.ledger..", "io.dargent.notifications..")
                .check(PRODUCTION);
    }

    /**
     * M0 acceptance criterion: "ArchUnit rejects an illegal import (test proving the gate)".
     * The fixture under architecture.fixture.domain carries a Spring annotation on purpose;
     * the same purity rule applied to it MUST fail — otherwise the two tests above would be
     * vacuously green forever.
     */
    @Test
    void boundary_gate_rejects_a_deliberate_domain_violation() {
        var fixtures = new ClassFileImporter().importPackages("io.dargent.payments.architecture.fixture");
        assertThatThrownBy(() -> domainPurityRule().check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("org.springframework.stereotype.Component");
    }

    private static ArchRule domainPurityRule() {
        return noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta..",
                        "com.fasterxml.jackson..",
                        "tools.jackson..",
                        "software.amazon.awssdk..");
    }
}
