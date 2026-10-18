package com.nayzawoo.expense.identity;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/household")
class HouseholdController {

    private final HouseholdRepository households;

    HouseholdController(HouseholdRepository households) {
        this.households = households;
    }

    @GetMapping
    HouseholdResponse household() {
        Household household = households.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new HouseholdResponse(
                household.getId().toString(),
                household.getName(),
                household.getCurrency(),
                household.getCurrencyScale(),
                household.getTimezone());
    }

    record HouseholdResponse(String id, String name, String currency, int currencyScale, String timezone) {}
}
