package io.dargent.notifications.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Notifications boundaries (AGENTS.md §2): consumer only, no business rules allowed to grow here. */
class NotificationsArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.dargent.notifications");

    @Test
    void notifications_never_reaches_into_sibling_modules() {
        noClasses()
                .that().resideInAPackage("io.dargent.notifications..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.dargent.payments..", "io.dargent.ledger..")
                .check(PRODUCTION);
    }
}
