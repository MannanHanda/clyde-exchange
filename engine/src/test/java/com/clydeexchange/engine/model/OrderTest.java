package com.clydeexchange.engine.model;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OrderTest {

    @Test
    void partialFillTest(){
        Order order = new Order(1, "MANN", Side.BUY, 10000, 100, OrderType.LIMIT);
        order.fill(30);
        assertEquals(70, order.getRemaining());
    }

    @Test
    void completeFillTest(){
        Order order = new Order(1, "MANN", Side.BUY, 10000, 100, OrderType.LIMIT);
        order.fill(100);
        assertTrue(order.isFilled());
    }

    @Test
    void overfillTest(){
        Order order = new Order(1, "MANN", Side.BUY, 10000, 100, OrderType.LIMIT);
        assertThrows(IllegalArgumentException.class , () -> order.fill(120));
    }


}
