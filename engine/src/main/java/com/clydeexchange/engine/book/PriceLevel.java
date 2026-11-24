package com.clydeexchange.engine.book;

import com.clydeexchange.engine.model.Order;

import java.util.ArrayDeque;

/** All resting orders at one price, oldest first (time priority), with their total remaining quantity. */
public class PriceLevel {

    private final long price;
    private final ArrayDeque<Order> orders = new ArrayDeque<>();
    private long totalQty;

    public PriceLevel(long price) {
        this.price = price;
    }

    void add(Order order) {
        totalQty += order.getRemaining();
        orders.addLast(order);
    }

    Order peek() {
        return orders.peek();
    }

    void fillFront(long qty) {
        Order front = orders.element();
        front.fill(qty);
        totalQty -= qty;
        if (front.isFilled()) orders.removeFirst();
    }

    void remove(Order order) {
        orders.remove(order);
        totalQty -= order.getRemaining();
    }

    boolean isEmpty() {
        return orders.isEmpty();
    }

    long getPrice() {
        return price;
    }

    long getTotalQty() {
        return totalQty;
    }
}
