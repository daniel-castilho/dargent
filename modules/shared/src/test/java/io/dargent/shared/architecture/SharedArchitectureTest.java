package io.dargent.shared.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * shared is the bottom of the dependency graph: it must never reach into business modules
 * (design.md §3.3 rule 6, AGENTS.md §2.1).
 */
class SharedArchitectureTest {

    private static final JavaClasses SHARED =
            new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("io.dargent.shared");

    @Test
    void shared_never_imports_business_modules() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.dargent.payments..", "io.dargent.ledger..", "io.dargent.notifications..")
                .check(SHARED);
    }
}
