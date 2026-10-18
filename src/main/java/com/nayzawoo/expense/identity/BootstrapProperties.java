package com.nayzawoo.expense.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "expense.bootstrap")
record BootstrapProperties(
        boolean enabled, String householdName, String adminEmail, String adminDisplayName, String adminPassword) {}
