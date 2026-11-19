package org.example.achat.infrastructure.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Inbound rate limit applied by the gateway to every routed request.
 *
 * <p>Bound from {@code gateway.rate-limit.*} in {@code api-gateway.yml} so the
 * limit can be tuned without code changes.
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public class InboundRateLimiterProperties {

    /** Maximum number of requests allowed per refresh window and client. */
    private int limitForPeriod = 20;

    /** Length of one token-bucket refresh window. */
    private Duration limitRefreshPeriod = Duration.ofSeconds(1);

    public int getLimitForPeriod() {
        return limitForPeriod;
    }

    public void setLimitForPeriod(int limitForPeriod) {
        this.limitForPeriod = limitForPeriod;
    }

    public Duration getLimitRefreshPeriod() {
        return limitRefreshPeriod;
    }

    public void setLimitRefreshPeriod(Duration limitRefreshPeriod) {
        this.limitRefreshPeriod = limitRefreshPeriod;
    }
}
