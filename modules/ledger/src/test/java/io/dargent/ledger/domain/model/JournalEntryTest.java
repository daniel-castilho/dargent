package io.dargent.ledger.domain.model;

import io.dargent.ledger.domain.exception.InvalidJournalEntryException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Constructor-side barrier for DEBT-5: validates double-entry invariants at the domain level.
 * <ul>
 *   <li>Σ DEBIT = Σ CREDIT</li>
 *   <li>≥ 2 postings</li>
 *   <li>All posting amounts > 0</li>
 * </ul>
 * The 4th-analysis cheats (pad, skip-line, sign hack) must be rejected.
 */
class JournalEntryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);

    // ---------------------------------------------------------------- valid entries

    @Test
    void balanced_two_postings_passes() {
        var postings = List.of(
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:A", EntryDirection.DEBIT, 1000, CLOCK.instant()),
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:B", EntryDirection.CREDIT, 1000, CLOCK.instant()));
        new JournalEntry(UUID.randomUUID(), UUID.randomUUID(), "TX1", UUID.randomUUID(), "test", CLOCK.instant(), postings);
    }

    @Test
    void balanced_three_postings_passes() {
        var postings = List.of(
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:A", EntryDirection.DEBIT, 500, CLOCK.instant()),
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:B", EntryDirection.DEBIT, 500, CLOCK.instant()),
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:C", EntryDirection.CREDIT, 1000, CLOCK.instant()));
        new JournalEntry(UUID.randomUUID(), UUID.randomUUID(), "TX2", UUID.randomUUID(), "test", CLOCK.instant(), postings);
    }

    // ---------------------------------------------------------------- rejections

    @Test
    void single_posting_rejected() {
        var postings = List.of(
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:A", EntryDirection.DEBIT, 1000, CLOCK.instant()));
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), UUID.randomUUID(), "TX", UUID.randomUUID(),
                "test", CLOCK.instant(), postings))
                .isInstanceOf(InvalidJournalEntryException.class)
                .hasMessageContaining("at least 2 postings");
    }

    @Test
    void unbalanced_debit_credit_rejected() {
        var postings = List.of(
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:A", EntryDirection.DEBIT, 1000, CLOCK.instant()),
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:B", EntryDirection.CREDIT, 900, CLOCK.instant()));
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), UUID.randomUUID(), "TX", UUID.randomUUID(),
                "test", CLOCK.instant(), postings))
                .isInstanceOf(InvalidJournalEntryException.class)
                .hasMessageContaining("must balance");
    }

    @Test
    void zero_amount_posting_rejected() {
        var postings = List.of(
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:A", EntryDirection.DEBIT, 0, CLOCK.instant()),
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:B", EntryDirection.CREDIT, 1000, CLOCK.instant()));
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), UUID.randomUUID(), "TX", UUID.randomUUID(),
                "test", CLOCK.instant(), postings))
                .isInstanceOf(InvalidJournalEntryException.class)
                .hasMessageContaining("amount must be positive");
    }

    @Test
    void negative_amount_posting_rejected() {
        var postings = List.of(
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:A", EntryDirection.DEBIT, -100, CLOCK.instant()),
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:B", EntryDirection.CREDIT, 100, CLOCK.instant()));
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), UUID.randomUUID(), "TX", UUID.randomUUID(),
                "test", CLOCK.instant(), postings))
                .isInstanceOf(InvalidJournalEntryException.class)
                .hasMessageContaining("amount must be positive");
    }

    // ---------------------------------------------------------------- 4th-analysis cheats

    @Test
    void pad_cheat_zero_amount_posting_rejected() {
        // Pad: add a zero-amount posting to make count ≥ 2 while keeping unbalanced
        var postings = List.of(
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:A", EntryDirection.DEBIT, 1000, CLOCK.instant()),
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:B", EntryDirection.CREDIT, 900, CLOCK.instant()),
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:C", EntryDirection.CREDIT, 0, CLOCK.instant()));
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), UUID.randomUUID(), "TX", UUID.randomUUID(),
                "test", CLOCK.instant(), postings))
                .isInstanceOf(InvalidJournalEntryException.class)
                .hasMessageContaining("amount must be positive");
    }

    @Test
    void sign_hack_rejected() {
        // Sign hack: try to balance by flipping direction but keeping amount positive (invalid)
        var postings = List.of(
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:A", EntryDirection.DEBIT, 1000, CLOCK.instant()),
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:B", EntryDirection.DEBIT, 1000, CLOCK.instant()));
        // Both DEBIT - no credit to balance
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), UUID.randomUUID(), "TX", UUID.randomUUID(),
                "test", CLOCK.instant(), postings))
                .isInstanceOf(InvalidJournalEntryException.class)
                .hasMessageContaining("must balance");
    }

    @Test
    void skip_line_rejected() {
        // Skip line: provide only one posting (already covered by single_posting_rejected)
        // But also test that removing a needed posting from a 3-line valid entry breaks balance
        var postings = List.of(
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:A", EntryDirection.DEBIT, 500, CLOCK.instant()),
                new Posting(UUID.randomUUID(), UUID.randomUUID(), "account:B", EntryDirection.DEBIT, 500, CLOCK.instant()));
        // Missing the 1000 CREDIT line
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), UUID.randomUUID(), "TX", UUID.randomUUID(),
                "test", CLOCK.instant(), postings))
                .isInstanceOf(InvalidJournalEntryException.class)
                .hasMessageContaining("must balance");
    }
}