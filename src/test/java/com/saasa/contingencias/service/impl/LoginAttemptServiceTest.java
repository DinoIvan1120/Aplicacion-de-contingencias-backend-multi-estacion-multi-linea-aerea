package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.security.ratelimit.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
/**
 * Tests unitarios para LoginAttemptService.
 *
 * No requiere contexto de Spring — LoginAttemptService no tiene dependencias externas.
 * Se usa ReflectionTestUtils para inyectar los valores de @Value sin levantar el contexto.
 */
@DisplayName("LoginAttemptService — Tests de rate limiting")
class LoginAttemptServiceTest {

    private LoginAttemptService service;

    private static final String IP_TEST     = "192.168.1.100";
    private static final String CORREO_TEST = "agente@saasa.com";

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
        // Inyectar valores de @Value manualmente (equivale a lo que haría Spring)
        ReflectionTestUtils.setField(service, "maxAttempts",    3);
        ReflectionTestUtils.setField(service, "blockDurationMs", 60_000L);  // 1 minuto
        ReflectionTestUtils.setField(service, "windowMs",        300_000L); // 5 minutos
    }

    @Test
    @DisplayName("No bloqueado inicialmente")
    void sinIntentos_noEstaBlockeado() {
        assertThat(service.isIpBlocked(IP_TEST)).isFalse();
        assertThat(service.isEmailBlocked(CORREO_TEST)).isFalse();
    }

    @Test
    @DisplayName("No bloquea antes de alcanzar maxAttempts")
    void intentosBajoElLimite_noBloquea() {
        service.loginFailed(IP_TEST, CORREO_TEST);
        service.loginFailed(IP_TEST, CORREO_TEST);
        // 2 intentos, maxAttempts = 3 → no bloqueado
        assertThat(service.isIpBlocked(IP_TEST)).isFalse();
        assertThat(service.isEmailBlocked(CORREO_TEST)).isFalse();
    }

    @Test
    @DisplayName("Bloquea al alcanzar maxAttempts")
    void intentosIgualesAlLimite_bloquea() {
        service.loginFailed(IP_TEST, CORREO_TEST);
        service.loginFailed(IP_TEST, CORREO_TEST);
        boolean activoBloqueo = service.loginFailed(IP_TEST, CORREO_TEST); // 3er intento

        assertThat(activoBloqueo).isTrue();
        assertThat(service.isIpBlocked(IP_TEST)).isTrue();
        assertThat(service.isEmailBlocked(CORREO_TEST)).isTrue();
    }

    @Test
    @DisplayName("El tercer intento devuelve true (bloqueo activado)")
    void tercerIntento_devuelveTrueUnicaVez() {
        boolean first  = service.loginFailed(IP_TEST, CORREO_TEST);
        boolean second = service.loginFailed(IP_TEST, CORREO_TEST);
        boolean third  = service.loginFailed(IP_TEST, CORREO_TEST);

        assertThat(first).isFalse();
        assertThat(second).isFalse();
        assertThat(third).isTrue();
    }

    @Test
    @DisplayName("Login exitoso resetea los contadores")
    void loginExitoso_resetea() {
        service.loginFailed(IP_TEST, CORREO_TEST);
        service.loginFailed(IP_TEST, CORREO_TEST);
        service.loginFailed(IP_TEST, CORREO_TEST); // bloqueado

        service.loginSucceeded(IP_TEST, CORREO_TEST);

        assertThat(service.isIpBlocked(IP_TEST)).isFalse();
        assertThat(service.isEmailBlocked(CORREO_TEST)).isFalse();
        assertThat(service.getAttemptCount(IP_TEST)).isZero();
    }

    @Test
    @DisplayName("remainingBlockSeconds > 0 cuando está bloqueado")
    void bloqueado_remainingPositivo() {
        service.loginFailed(IP_TEST, CORREO_TEST);
        service.loginFailed(IP_TEST, CORREO_TEST);
        service.loginFailed(IP_TEST, CORREO_TEST);

        long remaining = service.remainingBlockSeconds(IP_TEST, CORREO_TEST);
        assertThat(remaining).isPositive().isLessThanOrEqualTo(60L);
    }

    @Test
    @DisplayName("Bloqueo por IP independiente del correo")
    void bloqueo_ipIndependienteDeCorreo() {
        String otraIp = "10.0.0.1";
        // Solo bloquear la IP original
        service.loginFailed(IP_TEST, CORREO_TEST);
        service.loginFailed(IP_TEST, CORREO_TEST);
        service.loginFailed(IP_TEST, CORREO_TEST);

        // La otra IP no está bloqueada
        assertThat(service.isIpBlocked(otraIp)).isFalse();
    }

    @Test
    @DisplayName("getAttemptCount refleja los intentos actuales")
    void getAttemptCount_refleja() {
        assertThat(service.getAttemptCount(IP_TEST)).isZero();
        service.loginFailed(IP_TEST, CORREO_TEST);
        assertThat(service.getAttemptCount(IP_TEST)).isEqualTo(1);
        service.loginFailed(IP_TEST, CORREO_TEST);
        assertThat(service.getAttemptCount(IP_TEST)).isEqualTo(2);
    }
}
