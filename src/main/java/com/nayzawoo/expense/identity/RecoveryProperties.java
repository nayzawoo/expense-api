package com.nayzawoo.expense.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "expense.recovery")
record RecoveryProperties(boolean enabled, String adminEmail, String adminPassword) {}
