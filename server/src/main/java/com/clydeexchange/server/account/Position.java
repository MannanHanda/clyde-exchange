package com.clydeexchange.server.account;

/**
 * A holding in one symbol, tracked with the average-cost method most brokers use.
 *
 * qty is signed: positive = long (you own shares), negative = short (you owe shares).
 * cost is what the open position cost to build, with the same sign as qty, so the average
 * price paid is cost / qty. Adding to a position grows cost; reducing it "closes" a
 * proportional slice of cost and turns the difference into realised profit or loss.
 */
public class Position {

    private long qty;
    private long cost;

    /**
     * @param signedQty +n for a buy of n shares, -n for a sell
     * @return the profit (or loss, if negative) this fill locked in; 0 if it only added to the position
     */
    long apply(long signedQty, long price) {
        boolean adding = qty == 0 || (qty > 0) == (signedQty > 0);
        if (adding) {
            qty += signedQty;
            cost += signedQty * price;
            return 0;
        }

        // Reducing: close as much as we hold, at most. "closing" has the sign of the fill.
        long closing = (Math.abs(signedQty) <= Math.abs(qty)) ? signedQty : -qty;
        long costClosed = cost * -closing / qty;            // the slice of cost that belongs to those shares
        long realised = -closing * price - costClosed;      // what they went for, minus what they cost
        qty += closing;
        cost -= costClosed;

        // Anything left over flips the position to the other side (long -> short or short -> long).
        long opening = signedQty - closing;
        qty += opening;
        cost += opening * price;
        return realised;
    }

    public long qty() { return qty; }
    public long cost() { return cost; }

    /** Average price paid per share, in ticks, rounded. 0 when flat. */
    public long averagePrice() {
        return (qty == 0) ? 0 : Math.round((double) cost / qty);
    }

    /** Profit if the whole position were closed at this price. */
    public long unrealisedPnl(long markPrice) {
        return qty * markPrice - cost;
    }
}
