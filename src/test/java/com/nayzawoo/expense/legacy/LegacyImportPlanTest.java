package com.nayzawoo.expense.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyImportPlanTest {
    @TempDir
    Path directory;

    static LegacyImportPlan plan(byte[] bytes) {
        return LegacyImportPlan.read(bytes, ZoneId.of("UTC"), LocalDate.parse("2026-10-31"));
    }

    @Test
    void rebasesBothTransferLegsAndPreservesOriginalCutoverBalancesAndAllHistory() {
        var plan = plan(LegacyImportFixture.bytes());

        assertThat(plan.entries).hasSize(7);
        assertThat(plan.preOpeningEntries).isEqualTo(3);
        assertThat(plan.accounts.get("1").openedOn).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(plan.accounts.get("1").opening).isEqualByComparingTo("1170");
        assertThat(plan.accounts.get("1").expectedBalance).isEqualByComparingTo("1170");
        assertThat(plan.accounts.get("2").openedOn).isEqualTo(LocalDate.parse("2026-09-03"));
        assertThat(plan.accounts.get("2").opening).isEqualByComparingTo("180");
        assertThat(plan.accounts.get("2").expectedBalance).isEqualByComparingTo("400");
        assertThat(plan.categories.getFirst().icon()).isEqualTo("lucide:gamepad-2");
        assertThat(plan.beneficiaries.get("all").kind()).isEqualTo("HOUSEHOLD");
        assertThat(plan.users.getFirst().email()).isEqualTo("admin@example.com");
        assertThat(plan.entries.getFirst().recordedAt()).isEqualTo(Instant.parse("2026-10-05T12:00:00Z"));
        assertThat(plan(LegacyImportFixture.bytes()).entries.getFirst().id()).isEqualTo(plan.entries.getFirst().id());
    }

    @Test
    void renamedHouseBeneficiaryKeepsHouseholdKindAndHistoryReferences() {
        var plan = plan(LegacyImportFixture.replace("\"for_whom\":\"All\"", "\"for_whom\":\"House\""));
        assertThat(plan.beneficiaries).containsKey("house").doesNotContainKey("all");
        assertThat(plan.beneficiaries.get("house").kind()).isEqualTo("HOUSEHOLD");
        assertThat(plan.entries.stream().filter(entry -> entry.beneficiary() != null)
                .map(entry -> entry.beneficiary().name())).contains("House").doesNotContain("All");
    }

    @Test
    void honorsExplicitTimestampTimezoneWithoutChangingEffectiveDates() {
        var plan = LegacyImportPlan.read(LegacyImportFixture.bytes(), ZoneId.of("Asia/Yangon"), LocalDate.parse("2026-10-31"));
        assertThat(plan.entries.getFirst().recordedAt()).isEqualTo(Instant.parse("2026-10-05T05:30:00Z"));
        assertThat(plan.entries.getFirst().date()).isEqualTo(LocalDate.parse("2026-09-01"));
    }

    @Test
    void rejectsFractionalMoneyBrokenReferencesAndInconsistentAdjustments() {
        assertThatThrownBy(() -> plan(LegacyImportFixture.replace("200.00", "200.01")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whole MMK");
        assertThatThrownBy(() -> plan(LegacyImportFixture.replace("\"account_id\":\"1\"", "\"account_id\":\"999\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing source account");
        assertThatThrownBy(() -> plan(LegacyImportFixture.replace("1170.00", "1169.00")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Adjustment delta mismatch");
    }

    @Test
    void rejectsMissingTablesDuplicateIdsAndUnsupportedAuthentication() {
        assertThatThrownBy(() -> plan(LegacyImportFixture.replace("\"name\":\"incomes\"", "\"name\":\"other_table\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported table");
        assertThatThrownBy(() -> plan(LegacyImportFixture.replace("\"id\":\"2\"", "\"id\":\"1\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate id");
        assertThatThrownBy(() -> plan(LegacyImportFixture.replace("\"is_admin\":\"1\"",
                        "\"is_admin\":\"1\",\"two_factor_secret\":\"encrypted-secret\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("two-factor");
    }

    @Test
    void defaultsToOfflineDryRunAndRequiresExplicitDatabaseConfiguration() throws Exception {
        Path file = directory.resolve("export.json");
        Files.write(file, LegacyImportFixture.bytes());
        assertThat(LegacyImportCommand.run(new String[] {file.toString()}, Map.of())).isZero();
        assertThat(LegacyImportCommand.run(new String[] {file.toString(), "--apply"}, Map.of())).isEqualTo(1);
        assertThat(LegacyImportCommand.run(new String[] {file.toString(), "--apply", "--dry-run"}, Map.of())).isEqualTo(1);
        assertThat(LegacyImportCommand.run(new String[] {file.toString(), "--typo"}, Map.of())).isEqualTo(1);
    }

    @Test
    void rejectsFutureDatesAndInvalidJsonWithoutEchoingContents() {
        assertThatThrownBy(() -> plan(LegacyImportFixture.replace("2026-10-04", "2027-01-01")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Future transaction");
        assertThatThrownBy(() -> plan("{SECRET".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("SECRET");
    }
}
