package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.dto.request.*;
import com.saasa.contingencias.domain.dto.response.*;
import com.saasa.contingencias.domain.enumeration.ContingenciaEnum;
import com.saasa.contingencias.domain.enumeration.EstadoVueloEnum;
import com.saasa.contingencias.service.IVueloService;
import com.saasa.contingencias.util.ApiResponse;
import com.saasa.contingencias.util.SecurityHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/v1/vuelos")
@Tag(name = "Vuelos")
public class VueloController {

    private final IVueloService vueloService;
    private final SecurityHelper securityHelper;

    public VueloController(IVueloService vueloService, SecurityHelper securityHelper) {
        this.vueloService = vueloService;
        this.securityHelper = securityHelper;
    }

    @PostMapping("/registro")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(summary = "VISTA LÍDER — Crea vuelo + habilita recursos en una sola transacción")
    public ResponseEntity<ApiResponse<RegistroVueloResponse>> crearRegistroCompleto(
            @Valid @RequestBody RegistroVueloRequest request,
            @AuthenticationPrincipal UserDetails user) {
        RegistroVueloResponse response = vueloService.crearRegistroCompleto(
                request, securityHelper.getUsuarioId(user));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Vuelo " + response.codigoVuelo() + " registrado con " +
                                response.recursos().size() + " recurso(s) habilitado(s)", response));
    }

    @GetMapping("/{id}/registro")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "VISTA LÍDER — Obtiene vuelo + recursos habilitados")
    public ResponseEntity<ApiResponse<RegistroVueloResponse>> obtenerRegistroCompleto(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(vueloService.obtenerRegistroCompleto(id)));
    }

    @PutMapping("/{id}/registro")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(summary = "VISTA LÍDER — Actualiza vuelo + sincroniza recursos en una sola transacción")
    public ResponseEntity<ApiResponse<RegistroVueloResponse>> actualizarRegistroCompleto(
            @PathVariable Long id,
            @Valid @RequestBody RegistroVueloRequest request,
            @AuthenticationPrincipal UserDetails user) {
        RegistroVueloResponse response = vueloService.actualizarRegistroCompleto(
                id, request, securityHelper.getUsuarioId(user));
        return ResponseEntity.ok(ApiResponse.success(
                "Vuelo " + response.codigoVuelo() + " actualizado con " +
                        response.recursos().size() + " recurso(s) activo(s)", response));
    }

    /**
     * GET /vuelos/itinerario-hoy
     *
     * Devuelve SOLO los vuelos ACTIVOS cuya fechaVuelo = hoy (Lima UTC-5).
     * Este es el endpoint que alimenta el combo del itinerario del líder.
     *
     * Por qué un endpoint dedicado en lugar de reutilizar /buscar:
     *  - Semántica clara: "dame los vuelos de hoy para gestionar contingencias".
     *  - El cálculo de "hoy Lima" ocurre en el servidor con DateTimeUtil.hoyEnLima(),
     *    no en el cliente → consistente aunque el navegador esté en otra zona horaria.
     *  - Respuesta List<> en lugar de Page<> porque el líder necesita todos los vuelos
     *    del día de una sola vez para el combo, no paginar.
     */
    @GetMapping("/itinerario-hoy")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Vuelos del día actual (Lima) — alimenta el itinerario del líder")
    public ResponseEntity<ApiResponse<List<VueloResponse>>> itinerarioHoy() {
        return ResponseEntity.ok(ApiResponse.success(vueloService.obtenerItinerarioHoy()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA','LINEA_AEREA')")
    public ResponseEntity<ApiResponse<Page<VueloResponse>>> findAll(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(vueloService.findAll(pageable)));
    }

    @GetMapping("/buscar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA','LINEA_AEREA')")
    @Operation(summary = "Búsqueda dinámica de vuelos por múltiples campos")
    public ResponseEntity<ApiResponse<Page<VueloResponse>>> buscar(
            @RequestParam(required = false) String aerolinea,
            @RequestParam(required = false) String codigoVuelo,
            @RequestParam(required = false) String origen,
            @RequestParam(required = false) String destino,
            @RequestParam(required = false) ContingenciaEnum tipoContingencia,
            @RequestParam(required = false) EstadoVueloEnum estado,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                vueloService.buscar(aerolinea, codigoVuelo, origen, destino,
                        tipoContingencia, estado, pageable)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    public ResponseEntity<ApiResponse<VueloResponse>> create(
            @Valid @RequestBody VueloRequest request,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        vueloService.create(request, securityHelper.getUsuarioId(user))));
    }

    @PostMapping("/carga-masiva")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ApiResponse<CargaMasivaResponse>> cargarExcel(
            @RequestParam("archivo") MultipartFile archivo,
            @RequestParam(value = "estacionId", required = false) Long estacionId,
            // El Excel completo se registra dentro de UNA sola estación+línea
            // aérea (la del contexto activo del topbar), igual que la
            // creación manual de un vuelo — ya no se auto-crea una línea
            // aérea nueva por cada texto distinto que traiga la fila.
            @RequestParam(value = "lineaAereaId", required = false) Long lineaAereaId,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(ApiResponse.success(
                vueloService.cargarDesdeExcel(archivo, securityHelper.getUsuarioId(user), estacionId, lineaAereaId)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    public ResponseEntity<ApiResponse<VueloResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody VueloRequest request) {
        return ResponseEntity.ok(ApiResponse.success(vueloService.update(id, request)));
    }

    @PatchMapping("/{id}/anular")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    public ResponseEntity<ApiResponse<Void>> anular(@PathVariable Long id) {
        vueloService.anular(id);
        return ResponseEntity.ok(ApiResponse.success("Vuelo anulado", null));
    }

    @PatchMapping("/{id}/habilitar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(summary = "Reactiva un vuelo anulado (ANULADO → ACTIVO)")
    public ResponseEntity<ApiResponse<Void>> habilitar(@PathVariable Long id) {
        vueloService.habilitar(id);
        return ResponseEntity.ok(ApiResponse.success("Vuelo habilitado exitosamente", null));
    }

    @GetMapping("/{id}/recursos")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    public ResponseEntity<ApiResponse<List<VueloRecursoResponse>>> findRecursos(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(vueloService.findRecursos(id)));
    }

    @PostMapping("/{id}/recursos")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    public ResponseEntity<ApiResponse<VueloRecursoResponse>> habilitarRecurso(
            @PathVariable Long id,
            @Valid @RequestBody VueloRecursoRequest request,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        vueloService.habilitarRecurso(id, request, securityHelper.getUsuarioId(user))));
    }

    @PatchMapping("/{id}/recursos/{rId}/deshabilitar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    public ResponseEntity<ApiResponse<Void>> deshabilitarRecurso(
            @PathVariable Long id, @PathVariable Long rId) {
        vueloService.deshabilitarRecurso(id, rId);
        return ResponseEntity.ok(ApiResponse.success("Recurso deshabilitado", null));
    }
}