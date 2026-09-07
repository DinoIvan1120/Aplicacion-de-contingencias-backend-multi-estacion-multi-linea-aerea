package com.saasa.contingencias.config.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.saasa.contingencias.domain.model.Auditoria;
import com.saasa.contingencias.domain.repository.AuditoriaRepository;
import com.saasa.contingencias.util.ApiResponse;
import com.saasa.contingencias.util.DateTimeUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * Filtro de rate limiting para el endpoint POST /api/v1/auth/login.
 *
 * Ejecuta ANTES de que el request llegue a AuthController.
 *
 * Responsabilidades:
 *   1. Extraer la IP real del cliente (respeta X-Forwarded-For de proxies/nginx).
 *   2. Bloquear el request con HTTP 429 si la IP o el correo están bloqueados.
 *   3. Envolver el body en CachedBodyRequestWrapper (implementación propia) para
 *      poder leerlo dos veces: una aquí para extraer el correo, otra en el controller.
 *   4. Tras el request: llamar a loginSucceeded() si volvió 200, loginFailed() si no.
 *   5. Si loginFailed() retorna true (bloqueo recién activado), persistir un
 *      registro en la tabla auditoria con acción LOGIN_BLOCKED.
 *
 * IMPORTANTE — Por qué CachedBodyRequestWrapper propio y no ContentCachingRequestWrapper:
 *   ContentCachingRequestWrapper de Spring solo cachea el body DESPUÉS de chain.doFilter(),
 *   nunca antes. Si se lee getInputStream() antes del chain, el InputStream del
 *   wrapper queda vacío y Spring no puede deserializar el @RequestBody del controller.
 *   El wrapper propio lee el InputStream original UNA sola vez en el constructor,
 *   guarda los bytes en byte[], y devuelve un InputStream fresco sobre ese array
 *   cada vez que se llama getInputStream() — sin importar cuántas veces.
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);
    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private final LoginAttemptService loginAttemptService;
    private final AuditoriaRepository auditoriaRepository;
    private final ObjectMapper objectMapper;

    public LoginRateLimitFilter(LoginAttemptService loginAttemptService,
                                AuditoriaRepository auditoriaRepository) {
        this.loginAttemptService = loginAttemptService;
        this.auditoriaRepository = auditoriaRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Solo aplicar el filtro al endpoint de login (POST)
        return !LOGIN_PATH.equals(request.getServletPath())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        // ── Usar CachedBodyRequestWrapper propio ────────────────────────────────
        // Lee el InputStream original UNA sola vez en el constructor y lo guarda
        // en byte[]. getInputStream() devuelve un stream fresco sobre ese array,
        // por lo que Spring puede deserializar @RequestBody sin problema.
        CachedBodyRequestWrapper wrappedRequest = new CachedBodyRequestWrapper(request);

        String ip     = resolveClientIp(request);
        // Extrae correo si existe, o dni si no hay correo (nuevo LoginRequest)
        String identificador = extractIdentificadorFromBody(wrappedRequest.getCachedBody());
        //String correo = extractCorreoFromBody(wrappedRequest.getCachedBody());

        // ── 1. Verificar bloqueo ANTES de procesar ──────────────────────────────
        //if (loginAttemptService.isIpBlocked(ip) || loginAttemptService.isEmailBlocked(correo)) {
          //  long remainingSec = loginAttemptService.remainingBlockSeconds(ip, correo);
            //log.warn("[RateLimit] Request bloqueado — ip={} correo={} remainingSec={}", ip, correo, remainingSec);
            //sendBlockedResponse(response, remainingSec);
            //return;
        //}

        // ── 1. Verificar bloqueo ANTES de procesar ──────────────────────────────
        if (loginAttemptService.isIpBlocked(ip) || loginAttemptService.isEmailBlocked(identificador)) {
            long remainingSec = loginAttemptService.remainingBlockSeconds(ip, identificador);
            log.warn("[RateLimit] Request bloqueado — ip={} identificador={} remainingSec={}", ip, identificador, remainingSec);
            sendBlockedResponse(response, remainingSec);
            return;
        }

        // ── 2. Dejar pasar el request al controller con el wrapper ──────────────
        chain.doFilter(wrappedRequest, response);

        // ── 3. Evaluar el resultado DESPUÉS de que el controller respondió ───────
        int status = response.getStatus();

        if (status == HttpStatus.OK.value()) {
            loginAttemptService.loginSucceeded(ip, identificador);
            log.debug("[RateLimit] Login exitoso — ip={} correo={}", ip, identificador);

        } else if (status == HttpStatus.BAD_REQUEST.value()
                || status == HttpStatus.UNAUTHORIZED.value()
                || status == HttpStatus.FORBIDDEN.value()) {
            boolean bloqueoActivado = loginAttemptService.loginFailed(ip, identificador);
            int intentos = loginAttemptService.getAttemptCount(ip);
            log.warn("[RateLimit] Login fallido — ip={} correo={} intentos={} bloqueado={}",
                    ip, identificador, intentos, bloqueoActivado);

            if (bloqueoActivado) {
                auditarBloqueo(ip, identificador);
            }
        }
    }

    // ── Respuesta 429 ──────────────────────────────────────────────────────────────

    private void sendBlockedResponse(HttpServletResponse response, long remainingSec) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Retry-After", String.valueOf(remainingSec));

        String msg = remainingSec > 0
                ? String.format("Demasiados intentos fallidos. Intente de nuevo en %d segundos.", remainingSec)
                : "Demasiados intentos fallidos. Intente de nuevo más tarde.";

        ApiResponse<Void> body = ApiResponse.error(msg);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    // ── Extraer IP real (respeta proxy / nginx) ────────────────────────────────────

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    // ─── Extraer identificador del body (correo o dni) ───────────────────────────

    /**
     * Lee el body cacheado y extrae el identificador de la sesión.
     * Prioriza "correo" (compatibilidad total); si no existe, usa "dni".
     * Ambos pueden aparecer en LoginRequest; correo siempre tiene prioridad.
     */
    private String extractIdentificadorFromBody(byte[] cachedBody) {
        try {
            if (cachedBody == null || cachedBody.length == 0) return "unknown";
            var node = objectMapper.readTree(cachedBody);

            var correoNode = node.get("correo");
            if (correoNode != null && !correoNode.isNull() && !correoNode.asText().isBlank()) {
                return correoNode.asText().toLowerCase().trim();
            }

            var dniNode = node.get("dni");
            if (dniNode != null && !dniNode.isNull() && !dniNode.asText().isBlank()) {
                return dniNode.asText().trim();
            }

        } catch (Exception e) {
            log.debug("[RateLimit] No se pudo extraer identificador del body: {}", e.getMessage());
        }
        return "unknown";
    }

    // ── Extraer correo del body ya cacheado ───────────────────────────────────────

    /**
     * Lee el body desde el byte[] ya cacheado por CachedBodyRequestWrapper.
     * NO consume ningún InputStream — solo parsea bytes ya en memoria.
     */
    private String extractCorreoFromBody(byte[] cachedBody) {
        try {
            if (cachedBody == null || cachedBody.length == 0) return "unknown";

            var node = objectMapper.readTree(cachedBody);
            var correoNode = node.get("correo");
            if (correoNode != null && !correoNode.isNull()) {
                return correoNode.asText().toLowerCase().trim();
            }
        } catch (Exception e) {
            log.debug("[RateLimit] No se pudo extraer correo del body: {}", e.getMessage());
        }
        return "unknown";
    }

    // ── Auditoría de bloqueos ─────────────────────────────────────────────────────

    private void auditarBloqueo(String ip, String correo) {
        try {
            Auditoria auditoria = Auditoria.builder()
                    .accion("LOGIN_BLOCKED")
                    .modulo("AUTH")
                    .ipOrigen(ip)
                    .detalle(String.format(
                            "{\"correo\":\"%s\",\"motivo\":\"max_intentos_alcanzados\"}",
                            correo))
                    .entidadTipo("Usuario")
                    .entidadNombre(correo)
                    .creadoEn(DateTimeUtil.ahoraEnLima())
                    .build();
            auditoriaRepository.save(auditoria);
            log.info("[RateLimit] Bloqueo auditado — ip={} correo={}", ip, correo);
        } catch (Exception e) {
            log.error("[RateLimit] No se pudo auditar el bloqueo: {}", e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // INNER CLASS — CachedBodyRequestWrapper
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * Wrapper propio que resuelve la limitación de ContentCachingRequestWrapper:
     *
     *   - Lee el InputStream original UNA sola vez en el constructor.
     *   - Guarda los bytes en byte[] cachedBody.
     *   - getInputStream() devuelve siempre un ByteArrayInputStream fresco
     *     sobre ese byte[], sin importar cuántas veces se llame.
     *
     * Así el flujo queda:
     *   CachedBodyRequestWrapper(request)   ← lee body UNA vez
     *   extractCorreoFromBody(cachedBody)   ← lee del byte[], stream intacto
     *   chain.doFilter(wrappedRequest)      ← Spring llama getInputStream() → stream fresco
     *   AuthController @RequestBody         ← deserializa correctamente ✅
     */
    private static class CachedBodyRequestWrapper extends jakarta.servlet.http.HttpServletRequestWrapper {

        private final byte[] cachedBody;

        public CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            // Leer el InputStream original UNA sola vez
            this.cachedBody = request.getInputStream().readAllBytes();
        }

        public byte[] getCachedBody() {
            return cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return byteArrayInputStream.read();
                }

                @Override
                public boolean isFinished() {
                    return byteArrayInputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // No-op para requests síncronos (no se usa async aquí)
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
