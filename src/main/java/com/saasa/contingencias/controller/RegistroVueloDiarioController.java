package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.dto.request.RegistroVueloDiarioRequest;
import com.saasa.contingencias.domain.dto.response.AtencionResponse;
import com.saasa.contingencias.domain.dto.response.CapacidadComprometidaResponse;
import com.saasa.contingencias.domain.dto.response.DisponibilidadResponse;
import com.saasa.contingencias.domain.dto.response.RegistroVueloDiarioResponse;
import com.saasa.contingencias.service.IAtencionService;
import com.saasa.contingencias.service.IDisponibilidadService;
import com.saasa.contingencias.service.IRegistroVueloDiarioService;
import com.saasa.contingencias.util.ApiResponse;
import com.saasa.contingencias.util.SecurityHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Controller para gestión de registros diarios de vuelos.
 *
 * Flujo principal:
 *   1. Administrador carga vuelos masivamente (VueloController /carga-masiva)
 *   2. Líder SELECCIONA vuelos del itinerario y los registra para el día (POST /registros-diarios)
 *   3. Agente ve solo registros del día actual (GET /registros-diarios/hoy)
 *   4. Líder ve su historial agrupado por fecha (GET /registros-diarios/mis-registros)
 */
@RestController
@RequestMapping("/api/v1/registros-diarios")
@Tag(name = "Registros Diarios de Vuelos",
        description = "Gestión de registros diarios del líder para vuelos del itinerario")
public class RegistroVueloDiarioController {

    private final IRegistroVueloDiarioService registroService;
    private final IDisponibilidadService disponibilidadService;
    private final IAtencionService atencionService;
    private final SecurityHelper securityHelper;

    public RegistroVueloDiarioController(IRegistroVueloDiarioService registroService,
                                         IDisponibilidadService disponibilidadService,
                                         IAtencionService atencionService,
                                         SecurityHelper securityHelper) {
        this.registroService = registroService;
        this.disponibilidadService = disponibilidadService;
        this.atencionService = atencionService;
        this.securityHelper = securityHelper;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(summary = "LÍDER - Registra un vuelo del itinerario para el día")
    public ResponseEntity<ApiResponse<RegistroVueloDiarioResponse>> registrarVuelo(
            @Valid @RequestBody RegistroVueloDiarioRequest request,
            @AuthenticationPrincipal UserDetails user) {
        RegistroVueloDiarioResponse response = registroService.registrarVuelo(
                request, securityHelper.getUsuarioId(user));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Vuelo " + response.vueloItinerario().codigoVuelo() +
                                " registrado para " + response.fechaRegistro() +
                                " con " + response.recursos().size() + " recurso(s)",
                        response));
    }

    @GetMapping("/hoy")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "AGENTE - Obtiene registros del día actual")
    public ResponseEntity<ApiResponse<List<RegistroVueloDiarioResponse>>> obtenerRegistrosDelDia() {
        List<RegistroVueloDiarioResponse> registros = registroService.obtenerRegistrosDelDia();
        return ResponseEntity.ok(ApiResponse.success(
                registros.size() + " registro(s) para hoy", registros));
    }

    @GetMapping("/mis-registros")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(summary = "LÍDER - Obtiene mis registros (historial personal)")
    public ResponseEntity<ApiResponse<Page<RegistroVueloDiarioResponse>>> obtenerMisRegistros(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "Fecha inicial del rango (yyyy-MM-dd)") LocalDate fechaInicio,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "Fecha final del rango (yyyy-MM-dd)") LocalDate fechaFin,

            @PageableDefault(size = 20, sort = "fechaRegistro", direction = Sort.Direction.DESC)
            Pageable pageable,

            @AuthenticationPrincipal UserDetails user) {
        Page<RegistroVueloDiarioResponse> registros = registroService.obtenerMisRegistros(
                securityHelper.getUsuarioId(user), fechaInicio, fechaFin, pageable);
        return ResponseEntity.ok(ApiResponse.success(
                "Página " + (registros.getNumber() + 1) + " de " + registros.getTotalPages() +
                        " (" + registros.getTotalElements() + " registro(s) total)",
                registros));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Obtiene un registro por ID")
    public ResponseEntity<ApiResponse<RegistroVueloDiarioResponse>> obtenerPorId(
            @PathVariable @Parameter(description = "ID del registro") Long id) {
        return ResponseEntity.ok(ApiResponse.success(registroService.obtenerPorId(id)));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Operation(summary = "ADMIN - Lista todos los registros activos")
    public ResponseEntity<ApiResponse<Page<RegistroVueloDiarioResponse>>> obtenerTodos(
            @PageableDefault(size = 20, sort = "fechaRegistro", direction = Sort.Direction.DESC)
            Pageable pageable) {
        Page<RegistroVueloDiarioResponse> registros = registroService.obtenerTodos(pageable);
        return ResponseEntity.ok(ApiResponse.success(
                "Página " + (registros.getNumber() + 1) + " de " + registros.getTotalPages(),
                registros));
    }

    @GetMapping("/rango")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(summary = "Obtiene registros en un rango de fechas")
    public ResponseEntity<ApiResponse<Page<RegistroVueloDiarioResponse>>> obtenerPorRango(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "Fecha inicial (yyyy-MM-dd)", required = true) LocalDate fechaInicio,

            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "Fecha final (yyyy-MM-dd)", required = true) LocalDate fechaFin,

            @PageableDefault(size = 20, sort = "fechaRegistro", direction = Sort.Direction.DESC)
            Pageable pageable) {
        Page<RegistroVueloDiarioResponse> registros = registroService.obtenerPorRangoFechas(
                fechaInicio, fechaFin, pageable);
        return ResponseEntity.ok(ApiResponse.success(
                "Registros del " + fechaInicio + " al " + fechaFin, registros));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(summary = "LÍDER - Actualiza recursos de un registro")
    public ResponseEntity<ApiResponse<RegistroVueloDiarioResponse>> actualizarRecursos(
            @PathVariable @Parameter(description = "ID del registro") Long id,
            @Valid @RequestBody RegistroVueloDiarioRequest request,
            @AuthenticationPrincipal UserDetails user) {
        RegistroVueloDiarioResponse response = registroService.actualizarRecursos(
                id, request, securityHelper.getUsuarioId(user));
        return ResponseEntity.ok(ApiResponse.success(
                "Registro actualizado con " + response.recursos().size() + " recurso(s) activo(s)",
                response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(summary = "LÍDER - Elimina un registro (soft-delete)")
    public ResponseEntity<ApiResponse<Void>> eliminarRegistro(
            @PathVariable @Parameter(description = "ID del registro") Long id,
            @AuthenticationPrincipal UserDetails user) {
        registroService.eliminarRegistro(id, securityHelper.getUsuarioId(user));
        return ResponseEntity.ok(ApiResponse.success("Registro eliminado exitosamente", null));
    }

    @GetMapping("/existe")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(summary = "Verifica si un vuelo ya está registrado en una fecha")
    public ResponseEntity<ApiResponse<Boolean>> existeRegistro(
            @RequestParam @Parameter(description = "ID del vuelo del itinerario", required = true)
            Long vueloItinerarioId,

            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "Fecha a verificar (yyyy-MM-dd)", required = true)
            LocalDate fecha) {
        boolean existe = registroService.existeRegistro(vueloItinerarioId, fecha);
        return ResponseEntity.ok(ApiResponse.success(
                existe ? "El vuelo ya está registrado para esta fecha" : "El vuelo no está registrado",
                existe));
    }

    @GetMapping("/{id}/disponibilidad")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Obtener disponibilidad actualizada de recursos en tiempo real")
    public ResponseEntity<ApiResponse<DisponibilidadResponse>> obtenerDisponibilidad(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(disponibilidadService.obtenerDisponibilidad(id)));
    }

    /**
     * GET /registros-diarios/comprometido-hoy
     *
     * Devuelve un mapa { proveedorId → CapacidadComprometidaResponse } con
     * la capacidad ya comprometida hoy para cada proveedor que tiene recursos
     * asignados en algún vuelo del día.
     *
     * Param excludeRegistroId (opcional): cuando el líder está EDITANDO un
     * registro existente, se pasa su ID para que sus propios recursos no se
     * contabilicen como "ya comprometidos" (evita doble conteo).
     *
     * Un proveedor sin nada asignado hoy no aparece en el mapa (no es un error,
     * significa que tiene toda su capacidad disponible).
     *
     * El frontend llama este endpoint UNA sola vez al abrir la vista del líder
     * y lo usa para decorar el combo de proveedores con la info de comprometido.
     */
    @GetMapping("/comprometido-hoy")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(summary = "LÍDER — Capacidad ya comprometida hoy por proveedor (Camino B)")
    public ResponseEntity<ApiResponse<Map<Long, CapacidadComprometidaResponse>>> comprometidoHoy(
            @RequestParam(required = false) Long excludeRegistroId) {
        return ResponseEntity.ok(ApiResponse.success(
                registroService.obtenerCapacidadComprometidaHoy(excludeRegistroId)));
    }

    @GetMapping("/{id}/atenciones")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Listar atenciones de un registro diario")
    public ResponseEntity<ApiResponse<List<AtencionResponse>>> listarAtenciones(@PathVariable Long id) {
        List<AtencionResponse> atenciones = atencionService.findByRegistroVueloDiarioId(id);
        return ResponseEntity.ok(ApiResponse.success(
                atenciones.size() + " atención(es) registrada(s)", atenciones));
    }
}
