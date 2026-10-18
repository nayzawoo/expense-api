package com.nayzawoo.expense.identity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({BootstrapProperties.class, RecoveryProperties.class})
class IdentityConfiguration {}
