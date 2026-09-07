package com.saasa.contingencias.config.documentation;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.security.*;
import org.springframework.context.annotation.*;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                //.addServersItem(new io.swagger.v3.oas.models.servers.Server()
                        //.url("https://saasa-contingencias-qas.duckdns.org")
                        //.description("QAS"))
            .info(new Info()
                .title("SAASA – Proyecto de gestión de contingencias - Dino Iván Pérez Vásquez")
                .description("API REST para gestión operativa de pasajeros afectados por contingencias aéreas")
                .version("v1.0")
                .contact(new Contact().name("Dino Iván Pérez Vásquez"))
            )
            .addSecurityItem(new SecurityRequirement().addList("Bearer Auth"))
            .components(new Components().addSecuritySchemes("Bearer Auth",
                new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")));
    }
}
