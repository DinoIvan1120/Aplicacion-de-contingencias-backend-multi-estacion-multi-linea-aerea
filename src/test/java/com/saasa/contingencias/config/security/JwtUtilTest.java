package com.saasa.contingencias.config.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas del mecanismo de resolución de estación(es)+línea(s) aérea(s) al
 * iniciar sesión (Documento Funcional Multi-Estación v1.1, sección 9.2): el
 * JWT debe transportar el claim `scopes` (pares estación+línea aérea) para
 * que el resto de la aplicación lo lea sin volver a consultar la base de
 * datos en cada request. El claim `estacionIds` se mantiene por
 * compatibilidad y se deriva siempre de `scopes`.
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test_jwt_secret_minimum_32_characters_here!!");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 86400000L);
    }

    @Test
    void generateToken_conScopes_seRecuperanEnElMismoOrden() {
        List<ScopeEstacionLinea> scopes = List.of(
                new ScopeEstacionLinea(1L, 10L),
                new ScopeEstacionLinea(2L, 20L),
                new ScopeEstacionLinea(3L, null)   // Administrador de Estación 3, sin restricción de línea
        );
        String token = jwtUtil.generateToken("user@saasa.pe", "LIDER_SAASA", scopes);

        assertEquals("user@saasa.pe", jwtUtil.extractCorreo(token));
        assertEquals("LIDER_SAASA", jwtUtil.extractRol(token));
        assertEquals(scopes, jwtUtil.extractScopes(token));
        // estacionIds se deriva de los scopes (distinct, mismo orden de aparición)
        assertEquals(List.of(1L, 2L, 3L), jwtUtil.extractEstacionIds(token));
    }

    @Test
    void generateToken_listaVacia_representaAdministradorGlobal() {
        String token = jwtUtil.generateToken("admin@saasa.pe", "ADMINISTRADOR", List.of());
        assertTrue(jwtUtil.extractEstacionIds(token).isEmpty());
        assertTrue(jwtUtil.extractScopes(token).isEmpty());
    }

    @Test
    void generateToken_conListaNull_noRompeYQuedaVacia() {
        String token = jwtUtil.generateToken("admin@saasa.pe", "ADMINISTRADOR", null);
        assertTrue(jwtUtil.extractEstacionIds(token).isEmpty());
        assertTrue(jwtUtil.extractScopes(token).isEmpty());
    }

    @Test
    void generateToken_overloadViejo_quedaComoAdministradorGlobalPorDefecto() {
        @SuppressWarnings("deprecation")
        String token = jwtUtil.generateToken("legacy@saasa.pe", "AGENTE_SAASA");
        assertTrue(jwtUtil.extractEstacionIds(token).isEmpty());
        assertTrue(jwtUtil.extractScopes(token).isEmpty());
    }

    @Test
    void generateToken_lineaAereaNull_representaTodasLasLineasDeEsaEstacion() {
        List<ScopeEstacionLinea> scopes = List.of(new ScopeEstacionLinea(1L, null));
        String token = jwtUtil.generateToken("user@saasa.pe", "ADMINISTRADOR_ESTACION", scopes);

        List<ScopeEstacionLinea> recuperados = jwtUtil.extractScopes(token);
        assertEquals(1, recuperados.size());
        assertEquals(1L, recuperados.get(0).estacionId());
        assertNull(recuperados.get(0).lineaAereaId());
    }

    @Test
    void isTokenValid_tokenBienFormado_true() {
        String token = jwtUtil.generateToken("user@saasa.pe", "AGENTE_SAASA",
                List.of(new ScopeEstacionLinea(1L, 1L)));
        assertTrue(jwtUtil.isTokenValid(token));
    }

    @Test
    void isTokenValid_tokenBasura_false() {
        assertFalse(jwtUtil.isTokenValid("esto-no-es-un-jwt"));
    }
}
