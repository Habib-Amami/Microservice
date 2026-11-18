package org.example.achat.product.application;

public class StockNotFoundException extends RuntimeException {

    public StockNotFoundException(Long stockId) {
        super("Stock not found with id: " + stockId);
    }
}
