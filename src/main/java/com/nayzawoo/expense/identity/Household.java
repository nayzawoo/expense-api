package com.nayzawoo.expense.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "household")
class Household {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "currency_scale", nullable = false)
    private short currencyScale;

    @Column(nullable = false)
    private String timezone;

    @Column(nullable = false)
    private boolean singleton = true;

    protected Household() {}

    static Household create(UUID id, String name) {
        Household household = new Household();
        household.id = id;
        household.name = name;
        household.currency = "MMK";
        household.currencyScale = 0;
        household.timezone = "Asia/Yangon";
        household.singleton = true;
        return household;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getCurrency() {
        return currency;
    }

    short getCurrencyScale() {
        return currencyScale;
    }

    String getTimezone() {
        return timezone;
    }
}
