package com.clydeexchange.engine.model;



public class Order {

    private final long id;
    private final long price;
    private final long quantity;
    private long remaining;
    private final String symbol;
    private final Side side;
    private final OrderType type;


    public Order(long id,  String symbol, Side side, long price, long quantity, OrderType type) {
        this.id = id;
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.remaining = quantity;
        this.type = type;
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

    public long fill(long qty) {
        if (qty > remaining) {
            throw new IllegalArgumentException("Fill " + qty + " exceeds remaining " + remaining);
        }
        else if (qty <= 0){
            throw new IllegalArgumentException("Quantity can't be <= 0");
        }
        else {
            remaining -= qty;
            return remaining;
        }
    }

    public boolean isFilled(){
        return remaining == 0;
    }

}