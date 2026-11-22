package com.clydeexchange.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The exchange.auth section of application.yml.
 *
 * @param signupCash    what a new trader starts with, in ticks (10000000 = £100,000)
 * @param signupEnabled false closes registration: existing users can still log in
 * @param maxUsers      registration closes by itself at this many users, so a public server cannot be filled with accounts
 */
@ConfigurationProperties("exchange.auth")
public record AuthProperties(
        @DefaultValue("10000000") long signupCash,
        @DefaultValue("true") boolean signupEnabled,
        @DefaultValue("1000") int maxUsers) {}
