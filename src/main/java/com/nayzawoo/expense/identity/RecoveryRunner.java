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
@ConditionalOnProperty(prefix = "expense.recovery", name = "enabled", havingValue = "true")
class RecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryRunner.class);

    private final RecoveryService recoveryService;
    private final RecoveryProperties properties;
    private final ConfigurableApplicationContext context;

    RecoveryRunner(
            RecoveryService recoveryService, RecoveryProperties properties, ConfigurableApplicationContext context) {
        this.recoveryService = recoveryService;
        this.properties = properties;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            RecoveryService.RecoveryResult result =
                    recoveryService.recover(properties.adminEmail(), properties.adminPassword());
            log.info(
                    "Recovery reset admin {}; password change is required before ordinary use",
                    result.adminEmail());
            System.exit(SpringApplication.exit(context, () -> 0));
        } catch (RuntimeException exception) {
            log.error("Recovery failed: {}", exception.getMessage());
            System.exit(SpringApplication.exit(context, () -> 1));
        }
    }
}
