package com.clydeexchange.server.service;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.account.AccountView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Stop orders, trading halts and the market summary, with a clock the tests control. */
class StopAndHaltTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final ExchangeService exchange = new ExchangeService(
            List.of(new Instrument("MANN", "Mannan Technologies", "Technology", 10_000),
                    new Instrument("CLYD", "Clyde Digital", "Technology", 5_000)),
            new CircuitBreaker.Settings(5, 60, 15),   // halt for 15 s on a 5% move within 60 s
            now::get);

    @BeforeEach
    void openAccounts() {
        exchange.openBotAccount("maker");
        exchange.openBotAccount("taker");
        exchange.openAccount("alice", 10_000);   // £100.00
    }

    @AfterEach
    void shutDown() {
        exchange.close();
    }

    private AccountView account(String id) {
        return exchange.account(id).orElseThrow();
    }

    /** Makes one trade of qty shares at this price between the two bot accounts. */
    private void tradeAt(long price, long qty) {
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, price, qty);
        exchange.placeOrder("taker", "MANN", Side.BUY, OrderType.LIMIT, price, qty);
    }

    /** Alice buys 10 MANN at 100, leaving her £90.00 in cash. */
    private void aliceBuysTen() {
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 100, 10);
        exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.MARKET, 0, 10);
    }

    // ---- stop orders ----

    @Test
    void aWaitingSellStopReservesTheShares() {
        aliceBuysTen();

        ExchangeService.OrderResult stop = exchange.placeOrder("alice", "MANN", Side.SELL, OrderType.STOP, 0, 90, 6);

        assertTrue(stop.trades().isEmpty());
        assertEquals(6, stop.restingQty());
        assertEquals(4, account("alice").positions().get(0).available());
        assertEquals(OrderType.STOP, account("alice").openOrders().get(0).type());
        assertEquals(90, account("alice").openOrders().get(0).stopPrice());
        assertThrows(IllegalArgumentException.class,
                () -> exchange.placeOrder("alice", "MANN", Side.SELL, OrderType.LIMIT, 200, 5));
        assertTrue(exchange.book("MANN", 10).asks().isEmpty(), "a stop is not on the book until it triggers");
    }

    @Test
    void aStopLossSellsWhenThePriceFallsToItAndTheMoneyMoves() {
        aliceBuysTen();
        exchange.placeOrder("alice", "MANN", Side.SELL, OrderType.STOP, 0, 96, 6);
        exchange.placeOrder("maker", "MANN", Side.BUY, OrderType.LIMIT, 97, 1);
        exchange.placeOrder("maker", "MANN", Side.BUY, OrderType.LIMIT, 96, 20);

        exchange.placeOrder("taker", "MANN", Side.SELL, OrderType.MARKET, 0, 1);   // trades at 97: stop not reached
        assertEquals(10, account("alice").positions().get(0).qty());

        ExchangeService.OrderResult result = exchange.placeOrder("taker", "MANN", Side.SELL, OrderType.LIMIT, 96, 5);

        assertEquals(1, result.trades().size(), "the taker's result lists only its own fill, not alice's");
        assertEquals(4, exchange.recentTrades("MANN", 50).size(), "but every trade is in the public history");
        AccountView alice = account("alice");
        assertEquals(4, alice.positions().get(0).qty());
        assertEquals(10_000 - 1_000 + 6 * 96, alice.cash());
        assertEquals(-24, alice.realisedPnl());   // bought 6 at 100, sold them at 96
        assertTrue(alice.openOrders().isEmpty());
    }

    @Test
    void aBuyStopNeedsALimitPriceSoTheCashCanBeReserved() {
        aliceBuysTen();   // last price is now 100

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.STOP, 0, 110, 5));
        assertEquals("A buy stop needs a limit price: use a STOP_LIMIT order", e.getMessage());

        exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.STOP_LIMIT, 120, 110, 50);
        assertEquals(6_000, account("alice").reservedCash());   // 50 x 120, the most it could cost
        assertEquals(3_000, account("alice").buyingPower());
    }

    @Test
    void cancellingAStopFreesWhatItReserved() {
        aliceBuysTen();
        long id = exchange.placeOrder("alice", "MANN", Side.SELL, OrderType.STOP, 0, 90, 10).orderId();

        assertFalse(exchange.cancelOrder("maker", "MANN", id), "not the maker's order");
        assertTrue(exchange.cancelOrder("alice", "MANN", id));

        assertEquals(10, account("alice").positions().get(0).available());
    }

    @Test
    void aStopOnTheWrongSideOfTheMarketIsRejected() {
        aliceBuysTen();
        assertThrows(IllegalArgumentException.class,
                () -> exchange.placeOrder("alice", "MANN", Side.SELL, OrderType.STOP, 0, 100, 5));
    }

    // ---- trading halts ----

    @Test
    void aFastFivePercentMoveHaltsTradingInThatSymbolOnly() {
        List<ExchangeService.Halt> halts = new ArrayList<>();
        exchange.addHaltListener(halts::add);
        tradeAt(100, 1);
        now.addAndGet(10_000);
        tradeAt(106, 1);   // 6% up in 10 seconds

        assertTrue(exchange.isHalted("MANN"));
        assertFalse(exchange.isHalted("CLYD"));
        assertEquals(1, halts.size());
        assertEquals(6.0, halts.get(0).movePercent(), 0.001);
        assertTrue(exchange.market().get(0).halted());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 106, 1));
        assertEquals("Trading in MANN is halted for another 15 seconds", e.getMessage());
        exchange.placeOrder("maker", "CLYD", Side.SELL, OrderType.LIMIT, 50, 1);   // other symbols carry on
    }

    @Test
    void ordersCanBeCancelledDuringAHaltAndTradingResumesAfterwards() {
        long resting = exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 150, 1).orderId();
        tradeAt(100, 1);
        tradeAt(94, 1);   // 6% down
        assertTrue(exchange.isHalted("MANN"));

        assertTrue(exchange.cancelOrder("maker", "MANN", resting));

        now.addAndGet(15_000);
        assertFalse(exchange.isHalted("MANN"));
        tradeAt(95, 1);   // measured from 94, where it halted: no new halt
        assertFalse(exchange.isHalted("MANN"));
    }

    @Test
    void aSlowMoveDoesNotHalt() {
        tradeAt(100, 1);
        now.addAndGet(61_000);   // the 60-second window has passed
        tradeAt(106, 1);

        assertFalse(exchange.isHalted("MANN"));
    }

    // ---- market summary and the bots' view of their money ----

    @Test
    void marketSummaryTracksLastOpenHighLowAndVolume() {
        MarketSummary before = exchange.market().get(0);
        assertEquals("MANN", before.symbol());
        assertEquals("Mannan Technologies", before.name());
        assertEquals(10_000, before.last(), "the start price until something trades");
        assertEquals(0, before.volume());

        tradeAt(100, 5);
        tradeAt(103, 2);
        tradeAt(99, 1);

        MarketSummary after = exchange.market().get(0);
        assertEquals(99, after.last());
        assertEquals(100, after.open());
        assertEquals(103, after.high());
        assertEquals(99, after.low());
        assertEquals(8, after.volume());
        assertEquals(5_000, exchange.market().get(1).last());   // CLYD untouched
    }

    @Test
    void fundsShowWhatIsFreeToTradeAfterReservations() {
        exchange.seedShares("alice", "MANN", 20, 100);   // a gift of 20 shares; her cash is untouched
        assertEquals(10_000, account("alice").cash());

        exchange.placeOrder("alice", "MANN", Side.SELL, OrderType.LIMIT, 200, 5);
        exchange.placeOrder("alice", "CLYD", Side.BUY, OrderType.LIMIT, 10, 300);

        OrderGateway.Funds funds = exchange.funds("alice", "MANN");
        assertEquals(7_000, funds.buyingPower());
        assertEquals(20, funds.held());
        assertEquals(15, funds.availableShares());
    }
}
