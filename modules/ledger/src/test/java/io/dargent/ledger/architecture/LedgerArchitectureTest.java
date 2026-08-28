package io.dargent.ledger.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Ledger boundaries (AGENTS.md §2): pure downstream consumer — never imports sibling modules. */
class LedgerArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.dargent.ledger");

    @Test
    void domain_layer_is_framework_free() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..", "com.fasterxml.jackson..",
                        "tools.jackson..", "software.amazon.awssdk..")
                .check(PRODUCTION);
    }

    @Test
    void ledger_never_reaches_into_sibling_modules() {
        noClasses()
                .that().resideInAPackage("io.dargent.ledger..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.dargent.payments..", "io.dargent.notifications..")
                .check(PRODUCTION);
    }
}
