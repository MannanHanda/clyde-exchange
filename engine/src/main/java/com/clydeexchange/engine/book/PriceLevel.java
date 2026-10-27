package com.clydeexchange.engine.book;

import com.clydeexchange.engine.model.Order;
import java.util.ArrayDeque;


public class PriceLevel {

    private final long price;
    private final ArrayDeque<Order> orders;
    private long totalQty;

    public PriceLevel(long price){
        this.price = price;
        this.totalQty = 0;
        this.orders = new ArrayDeque<>();
    }

    void add(Order order){
        totalQty+=order.getRemaining();
        orders.addLast(order);
    }

    Order peek(){return orders.peek();}

    void fillFront(long qty){
        Order firstElement = orders.element();
        firstElement.fill(qty);
        totalQty -= qty;
        if (firstElement.isFilled()) {
            orders.removeFirst();
        }
    }

    void remove(Order order){
        orders.remove(order);
        totalQty -= order.getRemaining();
    }

    boolean isEmpty(){return orders.isEmpty();}
    long getPrice() {return price;}
    long getTotalQty() {return totalQty;}
}
