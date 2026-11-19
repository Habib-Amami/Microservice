package org.example.achat.payment.infrastructure.client;

import org.springframework.cloud.openfeign.FeignFormatterRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.datetime.standard.DateTimeFormatterRegistrar;

import java.time.format.DateTimeFormatter;

/**
 * OpenFeign serializes java.time query parameters with the system default
 * format (e.g. {@code 1/1/26}); Procurement parses dates as ISO
 * ({@code 2026-01-01}), so date parameters must be registered explicitly.
 */
@Configuration
public class FeignDateFormatConfiguration {

    @Bean
    FeignFormatterRegistrar isoDateFeignFormatterRegistrar() {
        return registry -> {
            DateTimeFormatterRegistrar registrar = new DateTimeFormatterRegistrar();
            registrar.setDateFormatter(DateTimeFormatter.ISO_DATE);
            registrar.setTimeFormatter(DateTimeFormatter.ISO_TIME);
            registrar.setDateTimeFormatter(DateTimeFormatter.ISO_DATE_TIME);
            registrar.registerFormatters(registry);
        };
    }
}
