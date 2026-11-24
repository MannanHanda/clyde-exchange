package com.clydeexchange.engine.model;

public class Order {

    private final long id;
    private final long price;
    private final long quantity;
    private long remaining;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    private final String accountId;   // who placed it: the account whose cash and shares it trades with

    public Order(long id, String symbol, Side side, long price, long quantity, OrderType type, String accountId) {
        this.id = id;
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.remaining = quantity;
        this.type = type;
        this.accountId = accountId;
    }

    public long getId() {
        return id;
    }
    public String getSymbol() {
        return symbol;
    }
    public Side getSide() {
        return side;
    }
    public long getQuantity() {
        return quantity;
    }
    public long getRemaining() {
        return remaining;
    }
    public long getPrice() {
        return price;
    }
    public OrderType getType() {
        return type;
    }
    public String getAccountId() {
        return accountId;
    }

    public long fill(long qty) {
        if (qty <= 0) throw new IllegalArgumentException("Quantity can't be <= 0");
        if (qty > remaining) throw new IllegalArgumentException("Fill " + qty + " exceeds remaining " + remaining);
        remaining -= qty;
        return remaining;
    }

    public boolean isFilled() {
        return remaining == 0;
    }
}