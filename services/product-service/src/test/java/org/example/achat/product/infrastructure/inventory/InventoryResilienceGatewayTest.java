package org.example.achat.product.infrastructure.inventory;

import feign.FeignException;
import feign.Request;
import feign.Response;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.example.achat.product.api.dto.ProductResponse;
import org.example.achat.product.application.InventoryServiceUnavailableException;
import org.example.achat.product.application.StockNotFoundException;
import org.example.achat.product.infrastructure.inventory.dto.StockResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryResilienceGatewayTest {

    private static final String KEY = "11111111-1111-1111-1111-111111111111";

    private final InventoryClient client = mock(InventoryClient.class);
    private final InventoryResilienceGateway gateway = new InventoryResilienceGateway(client);

    private static FeignException inventoryError(int status) {
        Request request = Request.create(
                Request.HttpMethod.GET,
                "http://inventory-service/stocks/3",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                null);
        Response response = Response.builder()
                .status(status)
                .reason(status == 404 ? "Not Found" : "Unavailable")
                .request(request)
                .headers(Map.of())
                .body(new byte[0])
                .build();
        return FeignException.errorStatus("inventory call", response);
    }

    @Test
    void delegatesSuccessfulCallsToTheFeignClient() {
        StockResponse stock = new StockResponse(3L, "Main warehouse", 10, 2);
        ProductResponse assigned = new ProductResponse(8L, "P-1", "Pen", 1.5f, 3L, null);
        when(client.getStock(3L)).thenReturn(stock);
        when(client.assignStock(8L, 3L, KEY)).thenReturn(assigned);

        assertThat(gateway.getStock(3L)).isEqualTo(stock);
        assertThat(gateway.assignStock(8L, 3L, KEY)).isEqualTo(assigned);
        verify(client).getStock(3L);
        verify(client).assignStock(8L, 3L, KEY);
    }

    @Test
    void stockFallbackKeepsTheNotFoundSemantics() {
        assertThatThrownBy(() -> gateway.stockFallback(404L, inventoryError(404)))
                .isInstanceOf(StockNotFoundException.class)
                .hasMessage("Stock not found with id: 404");
    }

    @Test
    void stockFallbackWrapsTransportFailuresAsUnavailable() {
        assertThatThrownBy(() -> gateway.stockFallback(3L, inventoryError(503)))
                .isInstanceOf(InventoryServiceUnavailableException.class)
                .hasMessageContaining("Could not validate stock 3");
    }

    @Test
    void stockFallbackIsIdempotentAcrossNestedAspects() {
        InventoryServiceUnavailableException original =
                new InventoryServiceUnavailableException("rate limited");
        assertThatThrownBy(() -> gateway.stockFallback(3L, new IllegalStateException("wrapped", original)))
                .isSameAs(original);

        StockNotFoundException notFound = new StockNotFoundException(404L);
        assertThatThrownBy(() -> gateway.stockFallback(404L, notFound))
                .isSameAs(notFound);
    }

    @Test
    void assignmentFallbackMapsBothFailureKinds() {
        assertThatThrownBy(() -> gateway.assignmentFallback(8L, 3L, KEY, inventoryError(404)))
                .isInstanceOf(StockNotFoundException.class);
        assertThatThrownBy(() -> gateway.assignmentFallback(8L, 3L, KEY, inventoryError(503)))
                .isInstanceOf(InventoryServiceUnavailableException.class)
                .hasMessageContaining("Could not assign the product to stock 3");
    }

    @Test
    void bothOperationsAreProtectedByAllThreeResiliencePolicies() throws Exception {
        assertPolicies(InventoryResilienceGateway.class.getMethod("getStock", Long.class), "stockFallback");
        assertPolicies(InventoryResilienceGateway.class.getMethod(
                "assignStock", Long.class, Long.class, String.class), "assignmentFallback");
    }

    private static void assertPolicies(Method method, String fallback) {
        Retry retry = method.getAnnotation(Retry.class);
        RateLimiter rateLimiter = method.getAnnotation(RateLimiter.class);
        CircuitBreaker circuitBreaker = method.getAnnotation(CircuitBreaker.class);

        assertThat(retry).isNotNull();
        assertThat(rateLimiter).isNotNull();
        assertThat(circuitBreaker).isNotNull();
        assertThat(retry.name()).isEqualTo(InventoryResilienceGateway.INSTANCE);
        assertThat(rateLimiter.name()).isEqualTo(InventoryResilienceGateway.INSTANCE);
        assertThat(circuitBreaker.name()).isEqualTo(InventoryResilienceGateway.INSTANCE);
        assertThat(retry.fallbackMethod()).isEqualTo(fallback);
        assertThat(rateLimiter.fallbackMethod()).isEqualTo(fallback);
        assertThat(circuitBreaker.fallbackMethod()).isEqualTo(fallback);
    }
}
