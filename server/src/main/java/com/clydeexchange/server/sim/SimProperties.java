package com.clydeexchange.server.sim;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/** The exchange.sim section of application.yml. */
@ConfigurationProperties("exchange.sim")
public record SimProperties(boolean enabled, Map<String, Long> startPrices) {}
