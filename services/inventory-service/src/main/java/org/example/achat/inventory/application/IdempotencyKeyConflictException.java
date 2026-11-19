package org.example.achat.inventory.application;

/** A key that was already used for a different assignment must not be reused. */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String keyValue) {
        super("Idempotency key '" + keyValue + "' was already used for a different assignment");
    }
}
