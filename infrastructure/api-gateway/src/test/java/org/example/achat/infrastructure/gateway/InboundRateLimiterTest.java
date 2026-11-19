package org.example.achat.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InboundRateLimiterTest {

    private static InboundRateLimiterProperties properties(int limitForPeriod) {
        InboundRateLimiterProperties properties = new InboundRateLimiterProperties();
        properties.setLimitForPeriod(limitForPeriod);
        // Long enough that the window cannot refresh while the test runs.
        properties.setLimitRefreshPeriod(Duration.ofMinutes(1));
        return properties;
    }

    @Test
    void allowsUpToTheConfiguredLimitThenRejectsWithHeaders() {
        InboundRateLimiter limiter = new InboundRateLimiter(properties(2));

        Response first = limiter.isAllowed("product-service", "192.168.1.10").block();
        Response second = limiter.isAllowed("product-service", "192.168.1.10").block();
        Response third = limiter.isAllowed("product-service", "192.168.1.10").block();

        assertThat(first).isNotNull();
        assertThat(first.isAllowed()).isTrue();
        assertThat(first.getHeaders()).containsEntry("X-RateLimit-Limit", "2");
        assertThat(second.isAllowed()).isTrue();
        assertThat(third).isNotNull();
        assertThat(third.isAllowed()).isFalse();
        assertThat(third.getHeaders())
                .containsEntry("X-RateLimit-Limit", "2")
                .containsEntry("X-RateLimit-Remaining", "0")
                .containsEntry("Retry-After", "60");
    }

    @Test
    void keepsSeparateBucketsForDifferentClientsAndRoutes() {
        InboundRateLimiter limiter = new InboundRateLimiter(properties(1));

        assertThat(limiter.isAllowed("product-service", "192.168.1.10").block().isAllowed()).isTrue();
        assertThat(limiter.isAllowed("product-service", "192.168.1.10").block().isAllowed()).isFalse();
        assertThat(limiter.isAllowed("product-service", "192.168.1.11").block().isAllowed()).isTrue();
        assertThat(limiter.isAllowed("inventory-stock-service", "192.168.1.10").block().isAllowed()).isTrue();
    }

    @Test
    void reportsRemainingPermissionsInTheResponseHeaders() {
        InboundRateLimiter limiter = new InboundRateLimiter(properties(3));

        Response response = limiter.isAllowed("product-service", "192.168.1.10").block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders())
                .containsEntry("X-RateLimit-Limit", "3")
                .containsEntry("X-RateLimit-Remaining", "2")
                .doesNotContainKey("Retry-After");
    }
}
