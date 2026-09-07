package com.saasa.contingencias.config.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JwtUtil {

    private static final String CLAIM_ROL = "rol";

    /**
     * Claim con las estaciones a las que el usuario tiene acceso.
     * Se mantiene por compatibilidad (reportes/lugares que solo necesitan
     * el eje estación) y se deriva siempre de CLAIM_SCOPES.
     */
    private static final String CLAIM_ESTACION_IDS = "estacionIds";

    /**
     * Claim con el detalle fino estación+línea aérea del usuario
     * (extensión multi-estación / multi-línea aérea): lista de pares
     * {"e": estacionId, "l": lineaAereaId | null}. "l": null significa que
     * el usuario ve TODAS las líneas aéreas de esa estación (p. ej. un
     * Administrador de Estación sin restricción de línea). Lista vacía =
     * Administrador Global (sin restricción de estación ni de línea).
     */
    private static final String CLAIM_SCOPES = "scopes";
    private static final String SCOPE_ESTACION = "e";
    private static final String SCOPE_LINEA = "l";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** @deprecated usar {@link #generateToken(String, String, List)}: todo login debe resolver scopes. */
    @Deprecated
    public String generateToken(String correo, String rol) {
        return generateToken(correo, rol, Collections.emptyList());
    }

    /**
     * Genera el JWT incluyendo el detalle estación+línea aérea activo del
     * usuario. Pasar una lista vacía representa un Administrador Global.
     */
    public String generateToken(String correo, String rol, List<ScopeEstacionLinea> scopes) {
        List<ScopeEstacionLinea> scopesSeguros = scopes == null ? Collections.emptyList() : scopes;
        List<Long> estacionIds = scopesSeguros.stream()
                .map(ScopeEstacionLinea::estacionId)
                .distinct()
                .toList();
        List<Map<String, Object>> scopesClaim = scopesSeguros.stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put(SCOPE_ESTACION, s.estacionId());
                    m.put(SCOPE_LINEA, s.lineaAereaId());
                    return m;
                })
                .toList();
        return Jwts.builder()
                .subject(correo)
                .claim(CLAIM_ROL, rol)
                .claim(CLAIM_ESTACION_IDS, estacionIds)
                .claim(CLAIM_SCOPES, scopesClaim)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    public String extractCorreo(String token) {
        return getClaims(token).getSubject();
    }

    public String extractRol(String token) {
        return getClaims(token).get(CLAIM_ROL, String.class);
    }

    /** Lista de IDs de estación del claim del token. Vacía = Administrador Global. */
    @SuppressWarnings("unchecked")
    public List<Long> extractEstacionIds(String token) {
        List<?> raw = getClaims(token).get(CLAIM_ESTACION_IDS, List.class);
        if (raw == null) {
            return Collections.emptyList();
        }
        // jjwt deserializa los números del claim como Integer/Long según el tamaño;
        // se normaliza siempre a Long para el resto de la aplicación.
        return raw.stream()
                .map(v -> v instanceof Number n ? n.longValue() : Long.parseLong(v.toString()))
                .toList();
    }

    /** Pares estación+línea aérea del claim del token. Vacía = Administrador Global. */
    @SuppressWarnings("unchecked")
    public List<ScopeEstacionLinea> extractScopes(String token) {
        List<?> raw = getClaims(token).get(CLAIM_SCOPES, List.class);
        if (raw == null) {
            return Collections.emptyList();
        }
        return raw.stream()
                .map(o -> (Map<String, Object>) o)
                .map(m -> new ScopeEstacionLinea(
                        toLong(m.get(SCOPE_ESTACION)),
                        toLong(m.get(SCOPE_LINEA))))
                .toList();
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        return v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
    }

    public boolean isTokenValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
    // Alias semántico — el subject puede ser correo o documento
    public String extractSubject(String token) {
        return extractCorreo(token); // mismo método, nombre más honesto
    }

}