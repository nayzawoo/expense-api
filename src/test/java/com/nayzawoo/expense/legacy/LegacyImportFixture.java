package com.nayzawoo.expense.legacy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

final class LegacyImportFixture {
    static final String PASSWORD = "legacy-test-password";
    static final String HASH = new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2Y, 4).encode(PASSWORD);

    private LegacyImportFixture() {}

    static byte[] bytes() {
        List<Map<String, Object>> blocks = List.of(
                Map.of("type", "header", "version", "test"),
                Map.of("type", "database", "name", "legacy_test"),
                table("users", List.of(row("1", "name", "Test Admin", "email", "Admin@example.com", "password", HASH, "is_admin", "1"))),
                table("accounts", List.of(
                        account("1", "Cash", "cash", "1000.00"), account("2", "Wallet", "wallet", "200.00"))),
                table("categories", List.of(row("1", "name", "Food", "color", "#FF8800", "icon", "Gamepad2", "sort_order", "0"))),
                table("expenses", List.of(
                        transaction("1", "2026-09-01", "200.00", "category_id", "1", "account_id", "1", "account", "Old Cash", "for_whom", "All"),
                        transaction("2", "2026-10-02", "100.00", "category_id", "1", "account_id", "1", "account", "Cash", "for_whom", "Child"))),
                table("incomes", List.of(
                        transaction("1", "2026-09-02", "50.00", "account_id", "1", "account", "Cash", "for_whom", "All"),
                        transaction("2", "2026-10-01", "500.00", "account_id", "1", "account", "Cash", "for_whom", "All"))),
                table("transfers", List.of(
                        transaction("1", "2026-09-03", "20.00", "from_account_id", "1", "to_account_id", "2"),
                        transaction("2", "2026-10-03", "200.00", "from_account_id", "1", "to_account_id", "2"))),
                table("account_adjustments", List.of(transaction("1", "2026-10-04", "-30.00", "account_id", "1",
                        "balance_before", "1200.00", "actual_balance", "1170.00", "note", "Reconcile"))));
        return new JsonMapper().writeValueAsBytes(blocks);
    }

    static byte[] replace(String from, String to) {
        return new String(bytes(), StandardCharsets.UTF_8).replace(from, to).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> table(String name, List<Map<String, Object>> rows) {
        return Map.of("type", "table", "name", name, "database", "legacy_test", "data", rows);
    }

    private static Map<String, Object> account(String id, String name, String type, String opening) {
        return row(id, "name", name, "type", type, "owner_name", "Test Owner", "opening_balance", opening,
                "balance_started_at", "2026-09-28", "is_active", "1", "sort_order", "0");
    }

    private static Map<String, Object> transaction(String id, String date, String amount, String... attributes) {
        Map<String, Object> row = row(id, attributes);
        row.put("date", date);
        row.put("amount", amount);
        row.put("user_id", "1");
        return row;
    }

    private static Map<String, Object> row(String id, String... attributes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("created_at", "2026-10-05 12:00:00");
        row.put("updated_at", "2026-10-05 12:00:00");
        for (int i = 0; i < attributes.length; i += 2) {
            row.put(attributes[i], attributes[i + 1]);
        }
        return row;
    }
}
