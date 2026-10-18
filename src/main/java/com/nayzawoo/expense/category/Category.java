package com.nayzawoo.expense.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "category")
class Category {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 7)
    private String color;

    @Column(name = "icon_key", nullable = false, length = 64)
    private String iconKey;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Category() {}

    static Category create(
            UUID id,
            UUID householdId,
            String name,
            String color,
            String iconKey,
            int sortOrder,
            Instant now) {
        Category category = new Category();
        category.id = id;
        category.householdId = householdId;
        category.name = name;
        category.color = color;
        category.iconKey = iconKey;
        category.sortOrder = sortOrder;
        category.status = "ACTIVE";
        category.version = 0;
        category.createdAt = now;
        category.updatedAt = now;
        return category;
    }

    void update(String name, String color, String iconKey, Integer sortOrder, Instant now) {
        if (name != null) {
            this.name = name;
        }
        if (color != null) {
            this.color = color;
        }
        if (iconKey != null) {
            this.iconKey = iconKey;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
        this.version++;
        this.updatedAt = now;
    }

    void updateStatus(String status, Instant now) {
        this.status = status;
        this.version++;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getHouseholdId() {
        return householdId;
    }

    String getName() {
        return name;
    }

    String getColor() {
        return color;
    }

    String getIconKey() {
        return iconKey;
    }

    int getSortOrder() {
        return sortOrder;
    }

    String getStatus() {
        return status;
    }

    long getVersion() {
        return version;
    }
}
