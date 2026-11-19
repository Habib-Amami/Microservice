package org.example.achat.inventory.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Inventory Service API",
        version = "1.0",
        description = "Stock and Product assignment compatibility API"))
public class ApiDocumentationConfiguration {
}
