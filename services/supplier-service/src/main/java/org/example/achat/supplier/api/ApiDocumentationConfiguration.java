package org.example.achat.supplier.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Supplier Service API",
        version = "1.0",
        description = "Supplier master, activity sectors and internal reference API"))
public class ApiDocumentationConfiguration {
}
