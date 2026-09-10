package io.dargent.api.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * M5 hotfix (owner adjudication Option A): the blue-green upgrade contract is PROVEN as a test —
 * a database in the v1.1.0 shape (payments history up to V112, ledger up to V207, notifications
 * V301) must boot the full current migration set without the crash-loop "resolved migration not
 * applied to database: 113". Gap-versioned bands are pure by construction (no migration touches
 * another module's schema — AGENTS §2.4), so out-of-order application of a late lower-band
 * migration is inert; it RESTORES the §3.8 contract (release N+1 runs against release N's schema).
 *
 * <p>v1.1.0 population without drift: migration files are frozen post-ship (§3.8 — a file's
 * checksum is its identity), so the CURRENT classpath bytes of payments V1xx≤112, ledger
 * V2xx≤207, notifications V301 ARE the v1.1.0 release set. The seed copies exactly those (per
 * band ceiling) into a temp location — no git plumbing, no network, CI-shallow-checkout-proof.
 * Flyway's {@code target} alone cannot express this shape: a version ceiling of 301 would apply
 * V113 eagerly (113 &lt; 301 in version order, though it post-dates the v1.1.0 release).
 *
 * <p>Flow: (1) seed = migrate the v1.1.0-shaped location + a pre-M5 payments row; (2) upgrade =
 * migrate the CURRENT locations with the Option-A posture (out-of-order=true, mirrors
 * application.yaml) — without it, this call throws the exact "resolved migration not applied to
 * database: 113" crash-loop observed on a real 30h volume; (3) assert V113 lands AFTER V301 in
 * installed order and the expand-only backfill gives the pre-existing row rail='pix'.
 */
@Testcontainers
class OutOfOrderUpgradeIT {

    /** The v1.1.0 band ceilings (last version each band carried in that release). */
    private static final String PAYMENTS_DIR = "modules/payments/src/main/resources/db/migration/payments";

    private static final String LEDGER_DIR = "modules/ledger/src/main/resources/db/migration/ledger";
    private static final String NOTIFICATIONS_DIR =
            "modules/notifications/src/main/resources/db/migration/notifications";
    private static final int PAYMENTS_CEILING = 112;
    private static final int LEDGER_CEILING = 207;
    private static final int NOTIFICATIONS_CEILING = 301;

    private static final Pattern MIGRATION_FILE = Pattern.compile("V(\\d+)__.+\\.sql");

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir
    Path seedDir;

    @Test
    void v1_1_0_shaped_database_boots_the_full_migration_set_out_of_order() throws Exception {
        // (1) the v1.1.0 shape: current frozen files, per-band ceilings
        List<String> seedLocations = new ArrayList<>();
        seedLocations.add(copyCeilingBand(PAYMENTS_DIR, PAYMENTS_CEILING));
        seedLocations.add(copyCeilingBand(LEDGER_DIR, LEDGER_CEILING));
        seedLocations.add(copyCeilingBand(NOTIFICATIONS_DIR, NOTIFICATIONS_CEILING));

        Flyway seed = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(seedLocations.toArray(new String[0]))
                .baselineOnMigrate(true)
                .load();
        seed.migrate();

        // a pre-M5 payments row — the population V113 must backfill without touching
        try (var conn = postgres.createConnection("");
                var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                    insert into payments.payments (id, txid, merchant_id, amount_cents, status,
                        expires_at, created_at)
                    values (gen_random_uuid(), 'UPGRADETEST0000000000000A',
                        'a0000000-0000-4000-8000-000000000001', 5000, 'CONFIRMED', now(), now())
                    """);
        }

        // (2) the CURRENT set with the Option-A posture (mirrors application.yaml out-of-order=true)
        Flyway upgrade = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(
                        "classpath:db/migration/payments",
                        "classpath:db/migration/ledger",
                        "classpath:db/migration/notifications")
                .outOfOrder(true)
                .load();
        upgrade.migrate();

        // (3) V113 applied AFTER V301 (installed_rank order) — the late payments band landed late
        try (var conn = postgres.createConnection("")) {
            List<String> order = new ArrayList<>();
            try (var stmt = conn.createStatement();
                    var rs = stmt.executeQuery(
                            "select version from public.flyway_schema_history order by installed_rank")) {
                while (rs.next()) {
                    order.add(rs.getString(1));
                }
            }
            assertThat(order.indexOf("113")).isGreaterThan(order.indexOf("301"));

            // expand-only backfill: the pre-existing row carries rail='pix', nothing rewritten
            try (var stmt = conn.createStatement();
                    var rs = stmt.executeQuery("select rail from payments.payments limit 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("pix");
            }
        }
    }

    /**
     * Copies the CURRENT classpath migration files of one band, version-filtered to {@code ceiling},
     * into a temp location dir; returns the Flyway filesystem location. The current bytes ARE the
     * shipped bytes (§3.8 freezes migration content post-ship), so this reconstructs the release
     * population without git or network.
     */
    /**
     * Copies the CURRENT migration files of one band, version-filtered to {@code ceiling}, from
     * the repo's source tree into a temp location; returns the Flyway filesystem location. The
     * shipped bytes ARE the current bytes (§3.8 freezes migration content post-ship), so this
     * reconstructs the release population without git or network. Classpath listing cannot be
     * used here (module jars are opaque to Files.list), and git plumbing breaks under CI's
     * shallow checkout — the source tree is the honest, environment-proof source.
     */
    private String copyCeilingBand(String bandDirRelative, int ceiling) throws IOException {
        Path repoRoot = Path.of(".").toAbsolutePath();
        while (Files.notExists(repoRoot.resolve(".git")) && repoRoot.getParent() != null) {
            repoRoot = repoRoot.getParent();
        }
        Path bandDir = repoRoot.resolve(bandDirRelative);
        if (Files.notExists(bandDir)) {
            throw new IllegalStateException("band dir not found: " + bandDir);
        }
        Path targetDir = seedDir.resolve(Path.of(bandDirRelative).getFileName());
        Files.createDirectories(targetDir);
        int copied = 0;
        try (var files = Files.list(bandDir)) {
            for (Path file : files.toList()) {
                var m = MIGRATION_FILE.matcher(file.getFileName().toString());
                if (!m.matches() || Integer.parseInt(m.group(1)) > ceiling) {
                    continue;
                }
                Files.copy(file, targetDir.resolve(file.getFileName()));
                copied++;
            }
        }
        if (copied == 0) {
            throw new IllegalStateException("no migrations <= V" + ceiling + " found under " + bandDir);
        }
        return "filesystem:" + targetDir;
    }
}
