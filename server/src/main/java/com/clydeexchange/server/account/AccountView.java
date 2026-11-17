package com.clydeexchange.server.account;

import com.clydeexchange.engine.book.OpenOrder;

import java.util.List;

/**
 * Everything the portfolio panel shows for one account, as one consistent picture.
 *
 * reservedCash is money set aside for resting buy orders; buyingPower = cash - reservedCash is what
 * can still be spent. equity = cash + the market value of all positions: what the account is worth.
 */
public record AccountView(
        String accountId,
        long cash,
        long reservedCash,
        long buyingPower,
        long positionsValue,
        long equity,
        long realisedPnl,
        long unrealisedPnl,
        List<PositionView> positions,
        List<OpenOrder> openOrders,
        List<Account.Fill> fills) {

    /** available = qty minus shares already promised to resting sell orders. */
    public record PositionView(String symbol, long qty, long available, long averagePrice, long lastPrice,
                               long marketValue, long unrealisedPnl) {}
}
