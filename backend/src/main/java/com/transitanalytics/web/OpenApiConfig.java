package com.transitanalytics.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata. springdoc builds the rest from the controllers. Every operation requires the bearer JWT
 * from {@code POST /api/auth/login} unless it opts out with an empty {@code @SecurityRequirements}.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Transit Analytics API", version = "v1",
                description = "CRUD for the transit model plus live data. Reads need ADMIN or GUEST, writes need ADMIN."),
        security = @SecurityRequirement(name = OpenApiConfig.BEARER))
@SecurityScheme(name = OpenApiConfig.BEARER, type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {

    static final String BEARER = "bearerAuth";
}
