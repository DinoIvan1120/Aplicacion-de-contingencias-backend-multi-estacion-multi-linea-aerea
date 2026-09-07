package com.saasa.contingencias.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing
public class JpaConfig {
    // Al estar en una @Configuration separada, @WebMvcTest la excluye
    // automáticamente porque no es un @Controller ni está en la capa web.
    // La app real la carga igual porque @SpringBootApplication escanea todo.
}
