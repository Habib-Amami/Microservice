package org.example.achat.product.infrastructure.inventory;

import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.example.achat.product.api.dto.ProductResponse;
import org.example.achat.product.application.InventoryServiceUnavailableException;
import org.example.achat.product.application.StockNotFoundException;
import org.example.achat.product.infrastructure.inventory.dto.StockResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resilience4j-decorated access to inventory-service.
 *
 * <p>All synchronous calls from product-service go through this gateway so the
 * {@code inventoryService} instance in {@code product-service.yml} (retry,
 * rate limiter, circuit breaker and fallback) applies consistently. The
 * fallbacks translate transport failures into the domain exceptions already
 * handled by {@code ProductExceptionHandler}, so callers do not need to know
 * about Feign or Resilience4j.
 *
 * <p>{@link #assignStock} carries a caller-supplied idempotency key so every
 * retry of the same logical write uses the same key and inventory-service can
 * short-circuit a replay instead of applying the write twice.
 */
@Component
public class InventoryResilienceGateway {

    /** Name of the shared Resilience4j instance declared in product-service.yml. */
    public static final String INSTANCE = "inventoryService";

    private static final Logger log = LoggerFactory.getLogger(InventoryResilienceGateway.class);

    private final InventoryClient inventory;

    public InventoryResilienceGateway(InventoryClient inventory) {
        this.inventory = inventory;
    }

    @Retry(name = INSTANCE, fallbackMethod = "stockFallback")
    @RateLimiter(name = INSTANCE, fallbackMethod = "stockFallback")
    @CircuitBreaker(name = INSTANCE, fallbackMethod = "stockFallback")
    public StockResponse getStock(Long stockId) {
        return inventory.getStock(stockId);
    }

    @Retry(name = INSTANCE, fallbackMethod = "assignmentFallback")
    @RateLimiter(name = INSTANCE, fallbackMethod = "assignmentFallback")
    @CircuitBreaker(name = INSTANCE, fallbackMethod = "assignmentFallback")
    public ProductResponse assignStock(Long productId, Long stockId, String idempotencyKey) {
        return inventory.assignStock(productId, stockId, idempotencyKey);
    }

    /**
     * Fallback for stock validation. A 404 is a business "unknown stock", every
     * other failure (including a rejected rate-limit permit or an open circuit)
     * means inventory-service is temporarily unavailable.
     */
    public StockResponse stockFallback(Long stockId, Throwable throwable) {
        Throwable cause = rootCause(throwable);
        if (cause instanceof FeignException.NotFound) {
            throw new StockNotFoundException(stockId);
        }
        if (cause instanceof StockNotFoundException exception) {
            throw exception;
        }
        if (cause instanceof InventoryServiceUnavailableException exception) {
            throw exception;
        }
        log.warn("Inventory stock lookup failed for stock {}; fallback applied", stockId, throwable);
        throw new InventoryServiceUnavailableException(
                "Could not validate stock " + stockId + " with inventory-service");
    }

    /**
     * Fallback for the assignment call. ProductCreationService rolls the
     * catalog row back whenever this throws.
     */
    public ProductResponse assignmentFallback(Long productId, Long stockId, String idempotencyKey,
                                              Throwable throwable) {
        Throwable cause = rootCause(throwable);
        if (cause instanceof FeignException.NotFound) {
            throw new StockNotFoundException(stockId);
        }
        if (cause instanceof StockNotFoundException exception) {
            throw exception;
        }
        if (cause instanceof InventoryServiceUnavailableException exception) {
            throw exception;
        }
        log.warn("Inventory assignment failed for product {} / stock {}; fallback applied",
                productId, stockId, throwable);
        throw new InventoryServiceUnavailableException(
                "Could not assign the product to stock " + stockId);
    }

    /**
     * The fallback can be reached through several nested aspects (retry ->
     * circuit breaker -> rate limiter), so a translated exception may arrive
     * wrapped. Unwrap it before classifying so the mapping is idempotent.
     */
    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
