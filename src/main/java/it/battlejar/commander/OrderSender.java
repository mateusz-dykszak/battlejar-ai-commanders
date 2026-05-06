package it.battlejar.commander;

import it.battlejar.api.Order;

@FunctionalInterface
public interface OrderSender {
    void send(Order order);
}
