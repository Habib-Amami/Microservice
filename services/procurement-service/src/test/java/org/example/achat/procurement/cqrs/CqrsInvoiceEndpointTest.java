package org.example.achat.procurement.cqrs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.achat.procurement.infrastructure.client.OperatorClient;
import org.example.achat.procurement.infrastructure.client.ProductClient;
import org.example.achat.procurement.infrastructure.client.SupplierClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check of the CQRS split: REST command -> Axon aggregate -> event
 * -> projector -> REST query. No Feign calls are needed on the CQRS path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "CONFIG_SERVER_IMPORT=optional:configserver:http://localhost:8888",
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:procurement_cqrs;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class CqrsInvoiceEndpointTest {

    @MockitoBean
    private SupplierClient supplierClient;

    @MockitoBean
    private OperatorClient operatorClient;

    @MockitoBean
    private ProductClient productClient;

    @Autowired
    private Environment environment;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void commandsAreProjectedAndQueriesReadTheProjection() throws Exception {
        HttpResponse<String> created = request("POST", "/cqrs/factures",
                """
                {
                  "montantRemise": 1.5,
                  "montantFacture": 13.5,
                  "dateCreationFacture": "2026-04-15",
                  "fournisseurId": 7,
                  "archivee": false,
                  "lignes": [{
                    "produitId": 42,
                    "codeProduit": "P-42",
                    "libelleProduit": "Product",
                    "qteCommandee": 3,
                    "pourcentageRemise": 10,
                    "montantRemise": 1.5,
                    "prixTotalDetail": 13.5
                  }]
                }
                """);
        assertThat(created.statusCode()).isEqualTo(202);
        String id = objectMapper.readTree(created.body()).get("id").asText();
        assertThat(id).isNotBlank();

        HttpResponse<String> byId = request("GET", "/cqrs/factures/" + id, null);
        assertThat(byId.statusCode()).isEqualTo(200);
        JsonNode invoice = objectMapper.readTree(byId.body());
        assertThat(invoice.get("id").asText()).isEqualTo(id);
        assertThat(invoice.get("fournisseurId").asLong()).isEqualTo(7L);
        assertThat(invoice.get("montantFacture").floatValue()).isEqualTo(13.5f);
        assertThat(invoice.get("archivee").booleanValue()).isFalse();
        assertThat(invoice.get("lignes").get(0).get("produitId").asLong()).isEqualTo(42L);

        HttpResponse<String> all = request("GET", "/cqrs/factures", null);
        assertThat(all.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(all.body()).toString()).contains(id);

        assertThat(request("PUT", "/cqrs/factures/" + id + "/archive", null).statusCode())
                .isEqualTo(204);
        HttpResponse<String> archived = request("GET", "/cqrs/factures/" + id, null);
        assertThat(archived.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(archived.body()).get("archivee").booleanValue()).isTrue();

        // The second archive command is rejected by the aggregate as a conflict.
        assertThat(request("PUT", "/cqrs/factures/" + id + "/archive", null).statusCode())
                .isEqualTo(409);
        assertThat(request("GET", "/cqrs/factures/unknown-id", null).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        int port = Integer.parseInt(environment.getProperty("local.server.port"));
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
