package com.nayzawoo.expense.legacy;

import com.nayzawoo.expense.shared.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A validated phpMyAdmin snapshot; never logs or serializes imported credentials. */
final class LegacyImportPlan {
    private static final Set<String> TABLES = Set.of(
            "users", "accounts", "categories", "expenses", "incomes", "transfers", "account_adjustments");
    private static final Set<String> IGNORED_TABLES = Set.of(
            "jobs", "job_batches", "failed_jobs", "migrations", "cache", "cache_locks", "sessions",
            "personal_access_tokens", "password_reset_tokens");

    final String source;
    final String checksum;
    final ZoneId sourceTimezone;
    final UUID householdId;
    final List<User> users = new ArrayList<>();
    final List<Category> categories = new ArrayList<>();
    final Map<String, Account> accounts = new LinkedHashMap<>();
    final Map<String, Beneficiary> beneficiaries = new LinkedHashMap<>();
    final List<Entry> entries = new ArrayList<>();
    int preOpeningEntries;

    private LegacyImportPlan(String source, String checksum, ZoneId sourceTimezone) {
        this.source = source;
        this.checksum = checksum;
        this.sourceTimezone = sourceTimezone;
        this.householdId = id("household", "1");
    }

    static LegacyImportPlan read(byte[] bytes, ZoneId sourceTimezone, LocalDate today) {
        JsonNode root;
        try {
            root = JsonMapper.builder().build().readTree(bytes);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid JSON export (contents omitted)");
        }
        require(root != null && root.isArray(), "Expected a phpMyAdmin JSON array");
        Map<String, List<JsonNode>> tables = new LinkedHashMap<>();
        String source = null;
        for (JsonNode block : root) {
            String type = text(block, "type");
            if (type.equals("database")) {
                String name = text(block, "name");
                require(source == null || source.equals(name), "Export contains multiple databases");
                source = name;
            } else if (type.equals("table")) {
                String name = text(block, "name");
                String database = text(block, "database");
                require(source != null && source.equals(database), "Table database does not match export database");
                require(TABLES.contains(name) || IGNORED_TABLES.contains(name), "Unsupported table: " + name);
                require(!tables.containsKey(name), "Duplicate table: " + name);
                JsonNode data = block.get("data");
                require(data != null && data.isArray(), "Missing data array: " + name);
                List<JsonNode> rows = new ArrayList<>();
                Set<String> ids = new HashSet<>();
                for (JsonNode row : data) {
                    if (TABLES.contains(name)) {
                        String rowId = text(row, "id");
                        require(rowId.matches("[1-9][0-9]*") && ids.add(rowId), "Invalid or duplicate id in " + name);
                    }
                    rows.add(row);
                }
                tables.put(name, rows);
            } else {
                require(type.equals("header"), "Unsupported export block");
            }
        }
        require(source != null && tables.keySet().containsAll(TABLES), "Export is missing required business tables");
        LegacyImportPlan plan = new LegacyImportPlan(source, digest(bytes), sourceTimezone);
        Set<String> emails = new HashSet<>();
        for (JsonNode row : tables.get("users")) {
            String rowId = text(row, "id");
            String email = label(row, "email", 320).toLowerCase(Locale.ROOT);
            require(emails.add(email), "Duplicate normalized user email");
            String hash = text(row, "password");
            require(hash.matches("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}"),
                    "Unsupported password hash for user " + rowId);
            require(optional(row, "two_factor_secret") == null && optional(row, "two_factor_confirmed_at") == null,
                    "User " + rowId + " has two-factor authentication; migrate authentication explicitly first");
            plan.users.add(new User(rowId, plan.id("users", rowId), email, label(row, "name", 200), hash,
                    flag(row, "is_admin") ? "ADMIN" : "MEMBER"));
        }
        require(plan.users.stream().anyMatch(user -> user.role().equals("ADMIN")), "Export must include an admin");
        Set<String> categoryNames = new HashSet<>();
        for (JsonNode row : tables.get("categories")) {
            String rowId = text(row, "id");
            String name = label(row, "name", 120);
            require(categoryNames.add(normalize(name)), "Duplicate normalized category name");
            String color = text(row, "color");
            require(color.matches("#[A-Fa-f0-9]{6}"), "Invalid category color for " + rowId);
            String icon = text(row, "icon");
            String key = icon.startsWith("lucide:") ? icon : "lucide:" + icon
                    .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                    .replaceAll("([A-Za-z])([0-9])", "$1-$2").toLowerCase(Locale.ROOT);
            require(iconKeys().contains(key), "Unsupported category icon for " + rowId);
            plan.categories.add(new Category(rowId, plan.id("categories", rowId), name, color, key,
                    integer(row, "sort_order"), plan.timestamp(row, "created_at"), plan.timestamp(row, "updated_at")));
        }
        for (JsonNode row : tables.get("accounts")) {
            String rowId = text(row, "id");
            String type = text(row, "type").toUpperCase(Locale.ROOT);
            require(Set.of("BANK", "WALLET", "CASH").contains(type), "Unsupported account type for " + rowId);
            LocalDate cutover = LocalDate.parse(text(row, "balance_started_at"));
            require(!cutover.isAfter(today), "Future account opening date for " + rowId);
            plan.accounts.put(rowId, new Account(rowId, plan.id("accounts", rowId), label(row, "name", 120),
                    label(row, "owner_name", 120), type, flag(row, "is_active") ? "ACTIVE" : "ARCHIVED",
                    integer(row, "sort_order"), cutover, money(row, "opening_balance"),
                    plan.timestamp(row, "created_at"), plan.timestamp(row, "updated_at")));
        }
        require(!plan.accounts.isEmpty(), "Export contains no accounts");
        for (String table : List.of("expenses", "incomes", "transfers", "account_adjustments")) {
            for (JsonNode row : tables.get(table)) {
                String rowId = text(row, "id");
                String kind = switch (table) {
                    case "expenses" -> "EXPENSE";
                    case "incomes" -> "INCOME";
                    case "transfers" -> "TRANSFER";
                    default -> "ADJUSTMENT";
                };
                User actor = plan.user(text(row, "user_id"));
                LocalDate date = LocalDate.parse(text(row, "date"));
                require(!date.isAfter(today), "Future transaction: " + table + "/" + rowId);
                BigDecimal amount = money(row, "amount");
                require(kind.equals("ADJUSTMENT") ? amount.signum() != 0 : amount.signum() > 0,
                        "Invalid amount: " + table + "/" + rowId);
                Account account = kind.equals("TRANSFER") ? null : plan.account(text(row, "account_id"));
                Account from = kind.equals("TRANSFER") ? plan.account(text(row, "from_account_id")) : null;
                Account to = kind.equals("TRANSFER") ? plan.account(text(row, "to_account_id")) : null;
                require(from == null || from != to, "Transfer accounts must differ");
                Category category = kind.equals("EXPENSE") ? plan.category(text(row, "category_id")) : null;
                Beneficiary beneficiary = null;
                if (kind.equals("EXPENSE") || kind.equals("INCOME")) {
                    String name = label(row, "for_whom", 120);
                    String normalized = normalize(name);
                    beneficiary = plan.beneficiaries.computeIfAbsent(normalized, ignored -> new Beneficiary(
                            plan.id("beneficiaries", normalized), name,
                            Set.of("all", "house", "household").contains(normalized) ? "HOUSEHOLD" : "PERSON"));
                }
                String note = optional(row, "note");
                require(note == null || note.length() <= (kind.equals("ADJUSTMENT") ? 500 : 2000), "Note too long");
                BigDecimal before = kind.equals("ADJUSTMENT") ? money(row, "balance_before") : null;
                BigDecimal actual = kind.equals("ADJUSTMENT") ? money(row, "actual_balance") : null;
                require(before == null || actual.subtract(before).compareTo(amount) == 0, "Adjustment delta mismatch");
                String accountName = account == null ? null : account.name;
                if (kind.equals("EXPENSE") || kind.equals("INCOME")) {
                    accountName = label(row, "account", 120);
                }
                Entry entry = new Entry(plan.id(table, rowId), kind, actor, date, amount, account,
                        from, to, category, beneficiary, note, accountName, before, actual,
                        plan.timestamp(row, "created_at"));
                plan.entries.add(entry);
                boolean preOpening = false;
                for (Leg leg : entry.legs()) {
                    Account target = leg.account();
                    target.net = target.net.add(leg.amount());
                    target.postingCount++;
                    if (date.isBefore(target.openedOn)) {
                        target.openedOn = date;
                    }
                    if (date.isBefore(target.originalDate)) {
                        target.preOpeningNet = target.preOpeningNet.add(leg.amount());
                        preOpening = true;
                    }
                }
                if (preOpening) {
                    plan.preOpeningEntries++;
                }
            }
        }
        for (Account account : plan.accounts.values()) {
            account.opening = account.originalOpening.subtract(account.preOpeningNet);
            require(account.opening.abs().compareTo(Money.MAX_ABS) <= 0, "Rebased opening is out of range");
            account.expectedBalance = account.opening.add(account.net);
        }
        plan.entries.sort(Comparator.comparing(Entry::date).thenComparing(Entry::recordedAt).thenComparing(Entry::id));
        return plan;
    }

    UUID id(String table, String sourceId) {
        return UUID.nameUUIDFromBytes(("laravel-import:v1:" + source.length() + ":" + source + ":" + table + ":" + sourceId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private User user(String sourceId) {
        return users.stream().filter(user -> user.sourceId().equals(sourceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing source user " + sourceId));
    }

    private Category category(String sourceId) {
        return categories.stream().filter(category -> category.sourceId().equals(sourceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing source category " + sourceId));
    }

    private Account account(String sourceId) {
        Account account = accounts.get(sourceId);
        require(account != null, "Missing source account " + sourceId);
        return account;
    }

    private Instant timestamp(JsonNode row, String field) {
        String value = text(row, field).replace(' ', 'T');
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            LocalDateTime local = LocalDateTime.parse(value);
            require(sourceTimezone.getRules().getValidOffsets(local).size() == 1, "Ambiguous source timestamp");
            return local.atZone(sourceTimezone).toInstant();
        }
    }

    private static Set<String> iconKeys() {
        try (var stream = LegacyImportPlan.class.getResourceAsStream("/category-lucide-icons.txt")) {
            require(stream != null, "Missing category icon catalog");
            return Set.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip().split("\\R"));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read icon catalog");
        }
    }

    private static BigDecimal money(JsonNode row, String field) {
        try {
            BigDecimal value = new BigDecimal(text(row, field)).setScale(0, java.math.RoundingMode.UNNECESSARY);
            require(value.abs().compareTo(Money.MAX_ABS) <= 0, "Money out of range: " + field);
            return value;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Expected whole MMK: " + field);
        }
    }

    private static int integer(JsonNode row, String field) {
        return Integer.parseInt(text(row, field));
    }

    private static boolean flag(JsonNode row, String field) {
        String value = text(row, field);
        require(Set.of("0", "1", "true", "false").contains(value), "Invalid boolean: " + field);
        return value.equals("1") || value.equals("true");
    }

    private static String label(JsonNode row, String field, int maximum) {
        String value = text(row, field).trim();
        require(!value.isEmpty() && value.length() <= maximum && !value.contains("\n") && !value.contains("\r"),
                "Invalid label: " + field);
        return value;
    }

    private static String text(JsonNode row, String field) {
        String value = optional(row, field);
        require(value != null, "Missing field: " + field);
        return value;
    }

    private static String optional(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    record User(String sourceId, UUID id, String email, String name, String hash, String role) {
        @Override
        public String toString() {
            return "Imported user " + sourceId;
        }
    }

    record Category(String sourceId, UUID id, String name, String color, String icon, int sortOrder,
            Instant createdAt, Instant updatedAt) {}

    record Beneficiary(UUID id, String name, String kind) {}

    static final class Account {
        final String sourceId;
        final UUID id;
        final String name;
        final String owner;
        final String type;
        final String status;
        final int sortOrder;
        final LocalDate originalDate;
        final BigDecimal originalOpening;
        final Instant createdAt;
        final Instant updatedAt;
        LocalDate openedOn;
        BigDecimal opening;
        BigDecimal expectedBalance;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal preOpeningNet = BigDecimal.ZERO;
        long postingCount;

        Account(String sourceId, UUID id, String name, String owner, String type, String status, int sortOrder,
                LocalDate originalDate, BigDecimal originalOpening, Instant createdAt, Instant updatedAt) {
            this.sourceId = sourceId;
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.type = type;
            this.status = status;
            this.sortOrder = sortOrder;
            this.originalDate = originalDate;
            this.originalOpening = originalOpening;
            this.openedOn = originalDate;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    record Entry(UUID id, String kind, User actor, LocalDate date, BigDecimal amount, Account account,
            Account from, Account to, Category category, Beneficiary beneficiary, String note, String accountName,
            BigDecimal balanceBefore, BigDecimal actualBalance, Instant recordedAt) {
        List<Leg> legs() {
            if (kind.equals("TRANSFER")) {
                return List.of(new Leg(from, amount.negate()), new Leg(to, amount));
            }
            return List.of(new Leg(account, kind.equals("EXPENSE") ? amount.negate() : amount));
        }
    }

    record Leg(Account account, BigDecimal amount) {}
}
