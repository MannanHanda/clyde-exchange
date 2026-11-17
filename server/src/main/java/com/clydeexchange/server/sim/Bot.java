package com.clydeexchange.server.sim;

/** A trading strategy for one symbol. Called once per simulator tick with the state of its market. */
public interface Bot {

    void act(MarketState market);
}
