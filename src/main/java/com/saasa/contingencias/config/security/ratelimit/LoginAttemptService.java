package com.saasa.contingencias.config.security.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servicio de control de intentos de login fallidos.
 *
 * Estrategia de bloqueo doble:
 *   - Por IP:     bloquea si hay demasiados intentos desde la misma dirección.
 *                 Protege contra bots y ataques de fuerza bruta distribuidos.
 *   - Por correo: bloquea si hay demasiados intentos contra la misma cuenta.
 *                 Protege contra ataques dirigidos a un usuario específico.
 *
 * Implementación en memoria (ConcurrentHashMap):
 *   ✅ Sin dependencia de Redis ni infraestructura extra.
 *   ✅ Thread-safe — ConcurrentHashMap garantiza operaciones atómicas.
 *   ✅ Auto-expira — el bloqueo se levanta al transcurrir blockDurationMs.
 *   ⚠️  El estado se pierde al reiniciar la JVM (aceptable para dev y qa).
 *
 * Configuración (en application.properties via .env.{perfil}):
 *   rate-limit.max-attempts      → intentos antes de bloquear (default 5)
 *   rate-limit.block-duration-ms → duración del bloqueo en ms (default 15 min)
 *   rate-limit.window-ms         → ventana de tiempo para contar intentos (default 10 min)
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    // ── Configuración inyectada desde .env.{perfil} vía application.properties ──────

    @Value("${rate-limit.max-attempts:5}")
    private int maxAttempts;

    @Value("${rate-limit.block-duration-ms:900000}")    // 15 minutos por defecto
    private long blockDurationMs;

    @Value("${rate-limit.window-ms:600000}")             // 10 minutos por defecto
    private long windowMs;

    // ── Estado en memoria ──────────────────────────────────────────────────────────

    /**
     * Mapa: clave → registro de intentos.
     * Clave puede ser una IP ("ip::1.2.3.4") o un correo ("email::user@saasa.com").
     */
    private final ConcurrentHashMap<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    // ── API pública ─────────────────────────────────────────────────────────────────

    /**
     * Registra un intento fallido para la IP y el correo dados.
     * Llama a este método cuando AuthService lanza excepción por credenciales inválidas.
     *
     * @param ip     IP del cliente (extraída del request)
     * @param correo Correo enviado en el body del login
     * @return true si este intento provocó el bloqueo (para decidir si auditar)
     */
    public boolean loginFailed(String ip, String correo) {
        boolean ipBlocked    = registerFailure(ipKey(ip));
        boolean emailBlocked = registerFailure(emailKey(correo));

        if (ipBlocked || emailBlocked) {
            log.warn("[RateLimit] Bloqueo activado — ip={} correo={}", ip, correo);
            return true;
        }
        return false;
    }

    /**
     * Limpia los contadores para la IP y el correo tras un login exitoso.
     * Evita que un usuario legítimo que se equivocó varias veces quede bloqueado.
     *
     * @param ip     IP del cliente
     * @param correo Correo del usuario que acaba de autenticarse
     */
    public void loginSucceeded(String ip, String correo) {
        attempts.remove(ipKey(ip));
        attempts.remove(emailKey(correo));
        log.debug("[RateLimit] Contadores reseteados — ip={} correo={}", ip, correo);
    }

    /**
     * Consulta si una IP está actualmente bloqueada.
     *
     * @param ip IP del cliente
     * @return true si debe rechazarse el request con 429
     */
    public boolean isIpBlocked(String ip) {
        return isBlocked(ipKey(ip));
    }

    /**
     * Consulta si un correo está actualmente bloqueado.
     *
     * @param correo Correo enviado en el body del login
     * @return true si debe rechazarse el request con 429
     */
    public boolean isEmailBlocked(String correo) {
        return isBlocked(emailKey(correo));
    }

    /**
     * Devuelve los segundos restantes de bloqueo para una clave.
     * Retorna 0 si no está bloqueado.
     *
     * @return segundos restantes, 0 si no aplica
     */
    public long remainingBlockSeconds(String ip, String correo) {
        long ipRemaining    = remaining(ipKey(ip));
        long emailRemaining = remaining(emailKey(correo));
        return Math.max(ipRemaining, emailRemaining);
    }

    /**
     * Número de intentos fallidos actuales para una IP (útil para logs y auditoría).
     */
    public int getAttemptCount(String ip) {
        AttemptRecord r = attempts.get(ipKey(ip));
        if (r == null) return 0;
        if (isExpired(r.windowStart)) {
            attempts.remove(ipKey(ip));
            return 0;
        }
        return r.count;
    }

    // ── Helpers privados ──────────────────────────────────────────────────────────

    private boolean registerFailure(String key) {
        Instant now = Instant.now();

        AttemptRecord record = attempts.compute(key, (k, existing) -> {
            if (existing == null || isExpiredWindow(existing.windowStart)) {
                // Ventana expirada o primer intento: nueva ventana
                return new AttemptRecord(1, now, null);
            }
            existing.count++;
            if (existing.count >= maxAttempts && existing.blockedAt == null) {
                // Primer vez que alcanza el límite: registrar bloqueo
                existing.blockedAt = now;
            }
            return existing;
        });

        return record.count == maxAttempts; // true solo el instante del bloqueo
    }

    private boolean isBlocked(String key) {
        AttemptRecord r = attempts.get(key);
        if (r == null || r.blockedAt == null) return false;

        long elapsed = Instant.now().toEpochMilli() - r.blockedAt.toEpochMilli();
        if (elapsed >= blockDurationMs) {
            attempts.remove(key);   // Bloqueo expirado: limpiar
            return false;
        }
        return true;
    }

    private long remaining(String key) {
        AttemptRecord r = attempts.get(key);
        if (r == null || r.blockedAt == null) return 0;
        long elapsed = Instant.now().toEpochMilli() - r.blockedAt.toEpochMilli();
        long rem = (blockDurationMs - elapsed) / 1000;
        return Math.max(0, rem);
    }

    private boolean isExpiredWindow(Instant windowStart) {
        return Instant.now().toEpochMilli() - windowStart.toEpochMilli() > windowMs;
    }

    private boolean isExpired(Instant windowStart) {
        return isExpiredWindow(windowStart);
    }

    private String ipKey(String ip)       { return "ip::"    + ip; }
    private String emailKey(String email) { return "email::" + email.toLowerCase(); }

    // ── Record interno ────────────────────────────────────────────────────────────

    /**
     * Registro de intentos para una clave (IP o correo).
     * No es un record inmutable porque compute() necesita mutar count y blockedAt.
     */
    private static class AttemptRecord {
        int     count;
        Instant windowStart;
        Instant blockedAt;      // null hasta que se alcanza maxAttempts

        AttemptRecord(int count, Instant windowStart, Instant blockedAt) {
            this.count       = count;
            this.windowStart = windowStart;
            this.blockedAt   = blockedAt;
        }
    }
}
