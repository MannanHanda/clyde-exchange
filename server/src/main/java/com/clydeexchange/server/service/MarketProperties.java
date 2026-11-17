package com.clydeexchange.server.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** The exchange section of application.yml: what can be traded, and when trading is paused. */
@ConfigurationProperties("exchange")
public record MarketProperties(List<Instrument> instruments, CircuitBreaker.Settings circuitBreaker) {}
