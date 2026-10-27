package com.clydeexchange.engine.book;
import com.clydeexchange.engine.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PriceLevelTest {

    @Test
    void getTotalQtyTest(){
        PriceLevel level = new PriceLevel(100);
        Order alice = new Order(1, "MANN", Side.BUY, 100, 50, OrderType.LIMIT);
        Order bob   = new Order(2, "MANN", Side.BUY, 100, 30, OrderType.LIMIT);
        level.add(alice);
        level.add(bob);
        assertEquals(80,level.getTotalQty());
    }

    @Test
    void partialFillTest(){
        PriceLevel level = new PriceLevel(100);
        Order alice = new Order(1, "MANN", Side.BUY, 100, 50, OrderType.LIMIT);
        Order bob   = new Order(2, "MANN", Side.BUY, 100, 30, OrderType.LIMIT);
        level.add(alice);
        level.add(bob);
        level.fillFront(20);
        assertEquals(60, level.getTotalQty());
        assertSame(alice, level.peek());
    }

    @Test
    void fullFillTest(){
        PriceLevel level = new PriceLevel(100);
        Order alice = new Order(1, "MANN", Side.BUY, 100, 50, OrderType.LIMIT);
        Order bob   = new Order(2, "MANN", Side.BUY, 100, 30, OrderType.LIMIT);
        level.add(alice);
        level.add(bob);
        level.fillFront(50);
        assertEquals(30, level.getTotalQty());
        assertSame(bob, level.peek());
    }


    @Test
    void overfillFillTest(){
        PriceLevel level = new PriceLevel(100);
        Order alice = new Order(1, "MANN", Side.BUY, 100, 50, OrderType.LIMIT);
        Order bob   = new Order(2, "MANN", Side.BUY, 100, 30, OrderType.LIMIT);
        level.add(alice);
        level.add(bob);
        assertThrows(IllegalArgumentException.class , () -> level.fillFront(70));
        assertEquals(80, level.getTotalQty());
    }

    @Test
    void removeSubtractsOnlyRemainingQty(){
        PriceLevel level = new PriceLevel(100);
        Order alice = new Order(1, "MANN", Side.BUY, 100, 50, OrderType.LIMIT);
        Order bob   = new Order(2, "MANN", Side.BUY, 100, 30, OrderType.LIMIT);
        level.add(alice);
        level.add(bob);
        level.fillFront(20);   // Alice has 30 left
        level.remove(alice);
        assertEquals(30, level.getTotalQty());
        assertSame(bob, level.peek());
    }

    @Test
    void emptyTest(){
        PriceLevel level = new PriceLevel(100);
        Order alice = new Order(1, "MANN", Side.BUY, 100, 50, OrderType.LIMIT);
        level.add(alice);
        level.fillFront(50);
        assertTrue(level.isEmpty());
    }







}
