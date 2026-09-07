package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.dto.request.AsignarLineaAereaRequest;
import com.saasa.contingencias.domain.dto.request.EstacionRequest;
import com.saasa.contingencias.domain.dto.response.EstacionLineaAereaResponse;
import com.saasa.contingencias.domain.dto.response.EstacionResponse;
import com.saasa.contingencias.service.IEstacionService;
import com.saasa.contingencias.util.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Módulo Administrador — Gestión de Estaciones y Líneas Aéreas
 * (Fase 2 del cronograma, Documento Funcional Multi-Estación v1.1, sección 5.2).
 * GET /estaciones y GET /estaciones/{id}/lineas-aereas quedan abiertos a
 * cualquier usuario autenticado porque el selector de estación/línea del
 * login (sección 5.1) los necesita para TODOS los roles, no solo el admin.
 * FIX: las operaciones de escritura ahora exigen, además del rol
 * ADMINISTRADOR, que sea Administrador GLOBAL (@estacionContext.
 * esAdministradorGlobal()). Estaciones es un catálogo general del
 * sistema — no tiene sentido acotarlo a la estación de trabajo de un
 * Administrador de estación específica, y antes cualquier ADMINISTRADOR
 * podía crear/editar estaciones o habilitar líneas aéreas en ellas vía
 * API directa aunque la pantalla ya estuviera oculta para él en el
 * frontend.
 */
@RestController
@RequestMapping("/api/v1/estaciones")
@Tag(name = "Estaciones")
public class EstacionController {

    private final IEstacionService estacionService;

    public EstacionController(IEstacionService estacionService) {
        this.estacionService = estacionService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<EstacionResponse>>> findAll(
            @RequestParam(required = false) Integer estado) {
        return ResponseEntity.ok(ApiResponse.success(estacionService.findAll(estado)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EstacionResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(estacionService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMINISTRADOR') and @estacionContext.esAdministradorGlobal()")
    public ResponseEntity<ApiResponse<EstacionResponse>> create(@Valid @RequestBody EstacionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(estacionService.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMINISTRADOR') and @estacionContext.esAdministradorGlobal()")
    public ResponseEntity<ApiResponse<EstacionResponse>> update(@PathVariable Long id,
                                                                @Valid @RequestBody EstacionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(estacionService.update(id, request)));
    }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasRole('ADMINISTRADOR') and @estacionContext.esAdministradorGlobal()")
    public ResponseEntity<ApiResponse<Void>> changeEstado(@PathVariable Long id, @RequestParam Integer estado) {
        estacionService.changeEstado(id, estado);
        return ResponseEntity.ok(ApiResponse.success("Estado actualizado", null));
    }

    // ─── Líneas aéreas habilitadas en la estación ──────────────────────────
    @GetMapping("/{id}/lineas-aereas")
    @Operation(summary = "Líneas aéreas habilitadas en la estación",
            description = "Usado por el selector dependiente del login: primero se elige la estación, " +
                    "luego este endpoint alimenta el selector de línea aérea dentro de ella.")
    public ResponseEntity<ApiResponse<List<EstacionLineaAereaResponse>>> findLineasAereas(
            @PathVariable Long id, @RequestParam(required = false) Integer estado) {
        return ResponseEntity.ok(ApiResponse.success(estacionService.findLineasAereas(id, estado)));
    }

    @PostMapping("/{id}/lineas-aereas")
    @PreAuthorize("hasRole('ADMINISTRADOR') and @estacionContext.esAdministradorGlobal()")
    public ResponseEntity<ApiResponse<EstacionLineaAereaResponse>> asignarLineaAerea(
            @PathVariable Long id, @Valid @RequestBody AsignarLineaAereaRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(estacionService.asignarLineaAerea(id, request)));
    }

    @PatchMapping("/{id}/lineas-aereas/{lineaAereaId}/estado")
    @PreAuthorize("hasRole('ADMINISTRADOR') and @estacionContext.esAdministradorGlobal()")
    public ResponseEntity<ApiResponse<Void>> cambiarEstadoLineaAerea(
            @PathVariable Long id, @PathVariable Long lineaAereaId, @RequestParam Integer estado) {
        estacionService.cambiarEstadoLineaAerea(id, lineaAereaId, estado);
        return ResponseEntity.ok(ApiResponse.success("Estado actualizado", null));
    }

    // ─── Foto del aeropuerto (tarjeta del selector de estación del login) ──
    @PostMapping(value = "/{id}/foto", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMINISTRADOR') and @estacionContext.esAdministradorGlobal()")
    public ResponseEntity<ApiResponse<EstacionResponse>> subirFoto(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) throws IOException {
        EstacionResponse response = estacionService.subirFoto(id, file.getBytes(), file.getContentType());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * URL firmada temporal (15 min) para mostrar la foto en el frontend.
     * Abierta a cualquier usuario autenticado, igual que findAll/findById —
     * es solo lectura de un asset visual, no información sensible.
     */
    @GetMapping("/{id}/foto")
    public ResponseEntity<ApiResponse<String>> obtenerUrlFoto(@PathVariable Long id) {
        String url = estacionService.obtenerUrlFoto(id);
        return ResponseEntity.ok(ApiResponse.success(url));
    }
}
