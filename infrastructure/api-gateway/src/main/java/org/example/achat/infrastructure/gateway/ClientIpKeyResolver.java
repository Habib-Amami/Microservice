package org.example.achat.infrastructure.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Buckets inbound requests by client IP instead of by authenticated principal.
 *
 * <p>The gateway has no authentication today, so the default
 * {@code PrincipalNameKeyResolver} would resolve an empty key and deny every
 * request. The address is always present in the demo stack; the literal
 * fallback keeps direct unit tests deterministic.
 */
@Component
public class ClientIpKeyResolver implements KeyResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        InetSocketAddress address = exchange.getRequest().getRemoteAddress();
        if (address == null) {
            return Mono.just("unknown");
        }
        String key = address.getAddress() != null
                ? address.getAddress().getHostAddress()
                : address.getHostString();
        return Mono.just(key == null || key.isBlank() ? "unknown" : key);
    }
}
