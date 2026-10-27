package com.clydeexchange.engine.model;

public record Trade (long buyOrderId, long sellOrderId, long price, long qty){ }
