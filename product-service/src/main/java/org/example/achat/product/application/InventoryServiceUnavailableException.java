package org.example.achat.product.application;

public class InventoryServiceUnavailableException extends RuntimeException {

    public InventoryServiceUnavailableException(String detail) {
        super(detail);
    }
}
