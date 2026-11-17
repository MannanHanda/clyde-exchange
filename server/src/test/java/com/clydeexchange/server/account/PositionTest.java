package com.clydeexchange.server.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PositionTest {

    @Test
    void buyingMoreAveragesThePrice() {
        Position p = new Position();
        assertEquals(0, p.apply(10, 100));
        assertEquals(0, p.apply(30, 120));

        assertEquals(40, p.qty());
        assertEquals(115, p.averagePrice());   // (10 x 100 + 30 x 120) / 40
    }

    @Test
    void sellingPartRealisesProfitAndKeepsTheAverage() {
        Position p = new Position();
        p.apply(10, 100);

        assertEquals(80, p.apply(-4, 120));    // 4 x (120 - 100)

        assertEquals(6, p.qty());
        assertEquals(100, p.averagePrice());
        assertEquals(-60, p.unrealisedPnl(90));   // the remaining 6 are 10 under water each
    }

    @Test
    void sellingEverythingAtALossLeavesNothingBehind() {
        Position p = new Position();
        p.apply(10, 100);

        assertEquals(-150, p.apply(-10, 85));

        assertEquals(0, p.qty());
        assertEquals(0, p.cost());
        assertEquals(0, p.averagePrice());
    }

    @Test
    void shortPositionProfitsWhenThePriceFalls() {
        Position p = new Position();
        p.apply(-10, 100);                     // sold 10 it didn't own

        assertEquals(-10, p.qty());
        assertEquals(100, p.averagePrice());
        assertEquals(50, p.unrealisedPnl(95));
        assertEquals(40, p.apply(4, 90));      // bought 4 back cheaper: 4 x (100 - 90)
        assertEquals(-6, p.qty());
    }

    @Test
    void sellingMoreThanHeldFlipsToShortAtTheNewPrice() {
        Position p = new Position();
        p.apply(10, 100);

        assertEquals(200, p.apply(-15, 120));  // only the 10 that were held make a profit

        assertEquals(-5, p.qty());
        assertEquals(120, p.averagePrice());
    }
}
