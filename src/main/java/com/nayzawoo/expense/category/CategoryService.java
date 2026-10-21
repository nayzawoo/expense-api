package com.nayzawoo.expense.category;

import com.nayzawoo.expense.identity.ExpenseUser;
import com.nayzawoo.expense.identity.FinancialAccess;
import com.nayzawoo.expense.identity.FinancialAccess.LockedHouseholdActor;
import com.nayzawoo.expense.shared.AuditEventWriter;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class CategoryService {

    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Set<String> ICON_KEYS = Set.of(
            "food",
            "transport",
            "home",
            "health",
            "shopping",
            "bills",
            "education",
            "entertainment",
            "other",
            "groceries",
            "dining",
            "coffee",
            "car",
            "fuel",
            "travel",
            "utilities",
            "phone",
            "internet",
            "rent",
            "clothing",
            "beauty",
            "fitness",
            "pets",
            "gifts",
            "family",
            "work",
            "insurance",
            "savings",
            "charity",
            "subscriptions");

    private final CategoryRepository categories;
    private final FinancialAccess financialAccess;
    private final AuditEventWriter audit;
    private final Clock clock;

    CategoryService(
            CategoryRepository categories, FinancialAccess financialAccess, AuditEventWriter audit, Clock clock) {
        this.categories = categories;
        this.financialAccess = financialAccess;
        this.audit = audit;
        this.clock = clock;
    }

    List<Category> list(ExpenseUser actor, UUID householdId, String status) {
        financialAccess.requireHouseholdMember(actor, householdId);
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return categories.findByHouseholdIdOrderBySortOrderAscNameAscIdAsc(householdId);
        }
        if (!isStatus(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return categories.findByHouseholdIdAndStatusOrderBySortOrderAscNameAscIdAsc(householdId, status);
    }

    Category get(ExpenseUser actor, UUID householdId, UUID categoryId) {
        financialAccess.requireHouseholdMember(actor, householdId);
        return categories
                .findByIdAndHouseholdId(categoryId, householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    Category create(
            ExpenseUser actor, UUID householdId, String name, String color, String iconKey, Integer sortOrder) {
        LockedHouseholdActor locked = financialAccess.lockForFinancialWrite(actor, householdId);
        if (!locked.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String trimmedName = requireName(name);
        String normalizedColor = requireColor(color);
        String normalizedIcon = requireIcon(iconKey);
        if (categories.existsNormalizedName(householdId, trimmedName, null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        Category created = categories.save(Category.create(
                UUID.randomUUID(),
                householdId,
                trimmedName,
                normalizedColor,
                normalizedIcon,
                sortOrder == null ? 0 : sortOrder,
                clock.instant()));
        audit.record(
                householdId,
                locked.userId(),
                "CATEGORY_CREATED",
                "CATEGORY",
                created.getId(),
                "{\"name\":\"" + AuditEventWriter.escape(created.getName()) + "\"}");
        return created;
    }

    @Transactional
    Category update(
            ExpenseUser actor,
            UUID householdId,
            UUID categoryId,
            String name,
            String color,
            String iconKey,
            Integer sortOrder) {
        LockedHouseholdActor locked = financialAccess.lockForFinancialWrite(actor, householdId);
        if (!locked.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (name == null && color == null && iconKey == null && sortOrder == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        Category category = categories
                .findByIdAndHouseholdId(categoryId, householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String previousName = category.getName();
        String nextName = name == null ? null : requireName(name);
        String nextColor = color == null ? null : requireColor(color);
        String nextIcon = iconKey == null ? null : requireIcon(iconKey);
        if (nextName != null
                && !normalize(nextName).equals(normalize(previousName))
                && categories.existsNormalizedName(householdId, nextName, categoryId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        category.update(nextName, nextColor, nextIcon, sortOrder, clock.instant());
        Category saved = categories.save(category);
        if (nextName != null && !normalize(nextName).equals(normalize(previousName))) {
            audit.record(
                    householdId,
                    locked.userId(),
                    "CATEGORY_RENAMED",
                    "CATEGORY",
                    saved.getId(),
                    "{\"previousName\":\""
                            + AuditEventWriter.escape(previousName)
                            + "\",\"newName\":\""
                            + AuditEventWriter.escape(saved.getName())
                            + "\"}");
        }
        return saved;
    }

    @Transactional
    Category updateStatus(ExpenseUser actor, UUID householdId, UUID categoryId, String status) {
        LockedHouseholdActor locked = financialAccess.lockForFinancialWrite(actor, householdId);
        if (!locked.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (!isStatus(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        Category category = categories
                .findByIdAndHouseholdId(categoryId, householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String previous = category.getStatus();
        category.updateStatus(status, clock.instant());
        Category saved = categories.save(category);
        if (!previous.equals(status)) {
            audit.record(
                    householdId,
                    locked.userId(),
                    "CATEGORY_STATUS_CHANGED",
                    "CATEGORY",
                    saved.getId(),
                    "{\"previousStatus\":\""
                            + previous
                            + "\",\"newStatus\":\""
                            + status
                            + "\"}");
        }
        return saved;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return name.trim();
    }

    private static String requireColor(String color) {
        if (color == null || !COLOR.matcher(color).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return color.toUpperCase(Locale.ROOT);
    }

    private static String requireIcon(String iconKey) {
        if (iconKey == null || !ICON_KEYS.contains(iconKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return iconKey;
    }

    private static boolean isStatus(String status) {
        return "ACTIVE".equals(status) || "ARCHIVED".equals(status);
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
