package org.example.achat.infrastructure.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Application-level inbound limiter for Spring Cloud Gateway.
 *
 * <p>Exposes a plain Resilience4j token bucket as the gateway's
 * {@link RateLimiter} bean, so the {@code RequestRateLimiter} default filter
 * works without Redis or an external proxy manager. Buckets are kept per
 * route/client key (see {@link ClientIpKeyResolver}) and the response carries
 * standard {@code X-RateLimit-*} headers plus {@code Retry-After} on a 429.
 *
 * <p>The gateway deliberately has no authentication yet, so this limits
 * traffic rather than authorising it; it complements (and does not replace)
 * the per-call {@code inventoryService} limiter inside product-service.
 */
@Component
public class InboundRateLimiter implements RateLimiter<InboundRateLimiter.Config> {

    public static final String NAME = "inbound";

    private final InboundRateLimiterProperties properties;
    private final ConcurrentMap<String, io.github.resilience4j.ratelimiter.RateLimiter> limiters =
            new ConcurrentHashMap<>();

    public InboundRateLimiter(InboundRateLimiterProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        String bucketId = NAME + ":" + routeId + ":" + id;
        io.github.resilience4j.ratelimiter.RateLimiter limiter = limiters.computeIfAbsent(
                bucketId, ignored -> io.github.resilience4j.ratelimiter.RateLimiter.of(
                        bucketId,
                        io.github.resilience4j.ratelimiter.RateLimiterConfig.custom()
                                .limitForPeriod(properties.getLimitForPeriod())
                                .limitRefreshPeriod(properties.getLimitRefreshPeriod())
                                .timeoutDuration(Duration.ZERO)
                                .build()));

        boolean allowed = limiter.acquirePermission();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-RateLimit-Limit", String.valueOf(properties.getLimitForPeriod()));
        headers.put("X-RateLimit-Remaining",
                String.valueOf(Math.max(0, limiter.getMetrics().getAvailablePermissions())));
        if (!allowed) {
            headers.put("Retry-After",
                    String.valueOf(Math.max(1L, properties.getLimitRefreshPeriod().toSeconds())));
        }
        return Mono.just(new Response(allowed, headers));
    }

    // The gateway's Configurable contract is not used here: limits come from
    // InboundRateLimiterProperties, not from per-route filter arguments.
    @Override
    public Class<Config> getConfigClass() {
        return Config.class;
    }

    @Override
    public Config newConfig() {
        return new Config();
    }

    @Override
    public Map<String, Config> getConfig() {
        return Map.of();
    }

    /** Required by the gateway's {@link RateLimiter} SPI; intentionally empty. */
    public static class Config {
    }
}
