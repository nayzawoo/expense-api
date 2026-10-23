package com.nayzawoo.expense.legacy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Standalone command: no web server, bootstrap runner, schema migration, or application startup side effects. */
public final class LegacyImportCommand {
    private LegacyImportCommand() {}

    public static void main(String[] args) {
        System.exit(run(args, System.getenv()));
    }

    static int run(String[] args, Map<String, String> environment) {
        if (args.length == 0 || (args.length == 1 && args[0].equals("--help"))) {
            System.out.println("""
                    Usage: sh scripts/import-laravel.sh FILE [--dry-run | --check-db | --apply] [--source-timezone=UTC] [--replace-bootstrap]
                    Default: --dry-run validates the file and reports rebased openings without connecting to a database.
                    --check-db: inserts and verifies all records and deferred constraints, then rolls everything back.
                    --apply: commits the verified import. Repeating the same file/timezone is a no-op.
                    Database modes require DATABASE_URL (jdbc:postgresql:...), DATABASE_USERNAME and DATABASE_PASSWORD.
                    Initialize the target schema with this application's Flyway migrations first.
                    By default the target must have no household or business data; existing households are never merged.
                    --replace-bootstrap: explicitly replace a single-user setup with only the two default beneficiaries
                    and no accounts, categories, financial transactions, or idempotency records. Replacement is atomic.
                    Source timestamps default to UTC, matching Laravel config/app.php; transaction dates are preserved.
                    """);
            return args.length == 0 ? 2 : 0;
        }
        try {
            String mode = "--dry-run";
            ZoneId timezone = ZoneId.of("UTC");
            boolean replaceBootstrap = false;
            Set<String> options = new HashSet<>();
            for (int i = 1; i < args.length; i++) {
                String option = args[i];
                if (option.startsWith("--source-timezone=")) {
                    if (!options.add("timezone")) {
                        throw new IllegalArgumentException("Duplicate timezone option");
                    }
                    timezone = ZoneId.of(option.substring("--source-timezone=".length()));
                } else if (option.equals("--replace-bootstrap")) {
                    if (!options.add("replace-bootstrap")) {
                        throw new IllegalArgumentException("Duplicate bootstrap replacement option");
                    }
                    replaceBootstrap = true;
                } else if (Set.of("--dry-run", "--check-db", "--apply").contains(option)) {
                    if (!options.add("mode")) {
                        throw new IllegalArgumentException("Choose exactly one mode");
                    }
                    mode = option;
                } else {
                    throw new IllegalArgumentException("Unknown option (use --help)");
                }
            }
            Path path = Path.of(args[0]);
            if (Files.size(path) > 50 * 1024 * 1024) {
                throw new IllegalArgumentException("Export exceeds the 50 MiB command limit");
            }
            LegacyImportPlan plan = LegacyImportPlan.read(
                    Files.readAllBytes(path), timezone, LocalDate.now(ZoneId.of("Asia/Yangon")));
            System.out.printf("Validated: %d users, %d categories, %d accounts, %d transactions; %d pre-opening records.%n",
                    plan.users.size(), plan.categories.size(), plan.accounts.size(), plan.entries.size(), plan.preOpeningEntries);
            System.out.println("Source timestamp timezone: " + timezone + "; transaction dates preserved.");
            for (var account : plan.accounts.values()) {
                System.out.printf("Account #%s: opening %s / %s -> %s / %s MMK; expected balance %s MMK%n",
                        account.sourceId, account.originalDate, account.originalOpening, account.openedOn,
                        account.opening, account.expectedBalance);
            }
            if (mode.equals("--dry-run")) {
                System.out.println("DRY RUN: no database connection or writes.");
                return 0;
            }
            String url = required(environment, "DATABASE_URL");
            if (!url.startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException("DATABASE_URL must use jdbc:postgresql:");
            }
            try (var connection = DriverManager.getConnection(url,
                    required(environment, "DATABASE_USERNAME"), required(environment, "DATABASE_PASSWORD"))) {
                var result = new LegacyImportWriter().write(
                        connection, plan, mode.equals("--apply"), Instant.now(), replaceBootstrap);
                System.out.println(result);
            }
            return 0;
        } catch (IllegalArgumentException exception) {
            System.err.println("Import refused: " + exception.getMessage());
        } catch (Exception exception) {
            // Database diagnostics can contain full rows, including password hashes. Never print them here.
            System.err.println("Import failed; no partial import was committed. Check file access, database access, and schema migrations.");
        }
        return 1;
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set " + key + " explicitly for database modes");
        }
        return value;
    }
}
