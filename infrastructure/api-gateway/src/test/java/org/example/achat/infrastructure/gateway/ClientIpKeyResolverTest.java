package org.example.achat.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpKeyResolverTest {

    private final ClientIpKeyResolver resolver = new ClientIpKeyResolver();

    @Test
    void resolvesTheClientAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/stocks")
                        .remoteAddress(new InetSocketAddress("192.168.1.10", 51234))
                        .build());

        assertThat(resolver.resolve(exchange).block()).isEqualTo("192.168.1.10");
    }

    @Test
    void usesTheHostNameForAnUnresolvedAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/stocks")
                        .remoteAddress(InetSocketAddress.createUnresolved("client.example.test", 51234))
                        .build());

        assertThat(resolver.resolve(exchange).block()).isEqualTo("client.example.test");
    }

    @Test
    void fallsBackToAStableKeyWhenTheAddressIsMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/stocks").build());

        assertThat(resolver.resolve(exchange).block()).isEqualTo("unknown");
    }
}
