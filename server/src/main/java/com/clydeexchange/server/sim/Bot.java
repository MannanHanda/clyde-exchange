package com.clydeexchange.server.sim;

/** A trading strategy for one symbol. Called once per simulator tick with the current fair value. */
public interface Bot {

    void act(long fairValue);
}
