package com.clydeexchange.server.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/** The exchange.accounts section of application.yml: each trader's id and the cash (in ticks) they start with. */
@ConfigurationProperties("exchange.accounts")
public record AccountProperties(Map<String, Long> startingCash) {}
