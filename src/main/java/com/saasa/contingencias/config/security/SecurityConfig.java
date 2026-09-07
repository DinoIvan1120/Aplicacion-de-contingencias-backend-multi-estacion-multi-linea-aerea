package com.saasa.contingencias.config.security;

import com.saasa.contingencias.config.security.ratelimit.LoginRateLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final LoginRateLimitFilter loginRateLimitFilter;

    @Value("${cors.allowed-origin}")
    private String allowedOrigins;

    public SecurityConfig(JwtFilter jwtFilter,LoginRateLimitFilter loginRateLimitFilter) {

        this.jwtFilter = jwtFilter;
        this.loginRateLimitFilter = loginRateLimitFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(c -> c.disable())
            .cors(c -> c.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/login","/api/v1/auth/register",       // ← público (primer admin o con token) "/api/v1/auth/forgot-password",
                        "/api/v1/auth/forgot-password","/api/v1/auth/reset-password", "/api/v1/auth/refresh").permitAll()
                    .requestMatchers(HttpMethod.GET,
                            "/api/v1/auth/forgot-password/tiene-correo"
                    ).permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                    .requestMatchers("/api/ws/**").permitAll()
                    // Enlace corto público del voucher (WhatsApp/correo): /v/{correlativo}
                    .requestMatchers(HttpMethod.GET, "/v/**").permitAll()
                .anyRequest().authenticated()
            )
                // ── Orden de filtros: RateLimit → JWT ──────────────────────────────
                // LoginRateLimitFilter corre primero para rechazar con 429 antes
                // de que el JwtFilter o el controller hagan cualquier trabajo.
                .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
