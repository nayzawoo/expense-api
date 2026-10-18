package com.nayzawoo.expense.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "expense.bootstrap", name = "enabled", havingValue = "true")
class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final BootstrapService bootstrapService;
    private final BootstrapProperties properties;
    private final ConfigurableApplicationContext context;

    BootstrapRunner(
            BootstrapService bootstrapService, BootstrapProperties properties, ConfigurableApplicationContext context) {
        this.bootstrapService = bootstrapService;
        this.properties = properties;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            BootstrapService.BootstrapResult result = bootstrapService.bootstrap(
                    properties.householdName(),
                    properties.adminEmail(),
                    properties.adminDisplayName(),
                    properties.adminPassword());
            log.info(
                    "Bootstrap created household {} and admin {}; password change is required before ordinary use",
                    result.householdId(),
                    result.adminEmail());
            System.exit(SpringApplication.exit(context, () -> 0));
        } catch (RuntimeException exception) {
            log.error("Bootstrap failed: {}", exception.getMessage());
            System.exit(SpringApplication.exit(context, () -> 1));
        }
    }
}
