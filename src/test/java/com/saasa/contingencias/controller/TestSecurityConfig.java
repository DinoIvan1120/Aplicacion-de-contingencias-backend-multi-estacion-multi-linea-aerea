package com.saasa.contingencias.controller;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuración de seguridad mínima para @WebMvcTest.
 *
 * Por qué reemplaza la SecurityConfig real:
 * - SecurityConfig depende de JwtFilter, que a su vez necesita @Value("${jwt.secret}")
 *   y otros beans JPA que @WebMvcTest no carga.
 * - Esta config omite el JwtFilter y sólo configura lo mínimo para que
 *   @PreAuthorize funcione correctamente en los tests.
 * - Las rutas públicas de /auth/** se replican aquí para que AuthControllerTest
 *   pueda probar el comportamiento real de acceso anónimo (igual que en producción).
 *
 * @EnableMethodSecurity es obligatorio para que @PreAuthorize sea evaluado.
 */
@TestConfiguration
@EnableMethodSecurity
public class TestSecurityConfig {

    @Bean
    public SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/login", "/api/v1/auth/register",
                                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
                                "/api/v1/auth/refresh"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/forgot-password/tiene-correo").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v/**").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}