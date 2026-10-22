package com.nayzawoo.expense.category;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact Lucide icon vocabulary; legacy semantic keys remain supported separately. */
final class CategoryIconCatalog {
    private static final Set<String> KEYS = load();

    private CategoryIconCatalog() {}

    static boolean contains(String key) {
        return key != null && KEYS.contains(key);
    }

    private static Set<String> load() {
        var stream = CategoryIconCatalog.class.getResourceAsStream("/category-lucide-icons.txt");
        if (stream == null) {
            throw new IllegalStateException("Missing category icon catalog");
        }
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            Set<String> keys = reader.lines().collect(Collectors.toUnmodifiableSet());
            if (keys.isEmpty() || keys.stream().anyMatch(key ->
                    key.length() > 64 || !key.matches("lucide:[a-z0-9]+(?:-[a-z0-9]+)*"))) {
                throw new IllegalStateException("Invalid category icon catalog");
            }
            return keys;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load category icon catalog", exception);
        }
    }
}
