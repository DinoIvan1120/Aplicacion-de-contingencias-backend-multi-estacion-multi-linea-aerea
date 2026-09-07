package com.saasa.contingencias.config.exception;

import com.saasa.contingencias.util.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(RecursoNoEncontradoException ex) {
        log.warn("Recurso no encontrado: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(AccesoDenegadoException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(AccesoDenegadoException ex) {
        log.warn("Acceso denegado: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(PnrDuplicadoException.class)
    public ResponseEntity<ApiResponse<Void>> handlePnrDuplicado(PnrDuplicadoException ex) {
        log.warn("PNR duplicado: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler({BadRequestException.class, ProveedorInactivoException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(AppException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    // En GlobalExceptionHandler.java — agregar handler específico para errores de PDF:
    @ExceptionHandler(PdfGenerationException.class)
    public ResponseEntity<ApiResponse<Void>> handlePdfGeneration(PdfGenerationException ex) {
        log.error("Error al generar PDF", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("No se pudo generar el PDF: " + ex.getMessage()));
    }

    // ── NUEVO: Rate limiting ───────────────────────────────────────────────────────
    /**
     * Maneja TooManyRequestsException con HTTP 429.
     *
     * Nota: el LoginRateLimitFilter responde directamente con 429 sin pasar por aquí,
     * pero este handler cubre el caso en que se lance la excepción desde un servicio.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooManyRequests(TooManyRequestsException ex) {
        log.warn("Rate limit excedido: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // ── NUEVO: Body JSON inválido o mal formado (incluye errores de validación
    // lanzados desde compact constructors de records, ej. ActualizarPasajeroRequest) ──
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        while (cause != null && !(cause instanceof IllegalArgumentException)) {
            cause = cause.getCause();
        }

        String mensaje = (cause != null)
                ? cause.getMessage()
                : "El cuerpo de la petición no es válido o está mal formado";

        log.warn("Body inválido en request: {}", mensaje);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(mensaje));
    }

    // ── NUEVO: violación de restricción de BD (unique, not-null, FK, etc.) ──
    // Antes caía en el handler genérico de Exception y devolvía un 500 sin
    // pista alguna. Ej: 2+ atenciones con el mismo codigoAutorizacion.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.error("Violación de integridad de datos", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("No se pudo guardar la información: existe un conflicto de datos (posible valor duplicado)."));
    }

    // ── NUEVO: lock pesimista (findByIdForUpdate) sin poder tomarse a tiempo ──
    // Se dispara solo en el caso extremo de que el lock esperado por
    // asignarServicios (AtencionServiceImpl) o crearServicioHotel/Transporte/
    // Restaurante (ReporteServicioBuilderImpl) supere el tiempo de espera de
    // MySQL (innodb_lock_wait_timeout, 50s por defecto). En la práctica el
    // lock se sostiene solo milisegundos, así que esto casi nunca ocurre —
    // pero si pasa, antes caía en el handler genérico y el agente veía
    // "Error interno del servidor" sin ninguna pista. Ahora responde 409
    // (Conflict) con un mensaje claro y accionable.
    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleLockTimeout(
            org.springframework.dao.PessimisticLockingFailureException ex) {
        log.warn("Timeout esperando el lock de un recurso: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(
                        "Otro agente está reservando este mismo recurso en este momento. "
                                + "Intenta nuevamente en unos segundos."));
    }


    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(ApiResponse.error(errors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {

        // Dejar que Spring Security maneje sus propias excepciones
        if (ex instanceof AccessDeniedException accessDenied) {
            throw accessDenied;
        }

        log.error("Error inesperado", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Error interno del servidor"));
    }

}
