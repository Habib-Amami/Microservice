package org.example.achat.procurement.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Procurement Service API",
        version = "1.0",
        description = "Supplier invoices, CQRS commands/queries and internal reference API"))
public class ApiDocumentationConfiguration {
}
