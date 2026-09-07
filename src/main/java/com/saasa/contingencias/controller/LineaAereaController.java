package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.dto.request.LineaAereaRequest;
import com.saasa.contingencias.domain.dto.response.LineaAereaResponse;
import com.saasa.contingencias.service.ILineaAereaService;
import com.saasa.contingencias.util.ApiResponse;
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
 * Catálogo global de líneas aéreas (Fase 2, sección 5.2 del documento
 * funcional). GET queda abierto a cualquier usuario autenticado; la
 * escritura queda restringida a ADMINISTRADOR.
 */
@RestController
@RequestMapping("/api/v1/lineas-aereas")
@Tag(name = "Líneas Aéreas")
public class LineaAereaController {

    private final ILineaAereaService lineaAereaService;

    public LineaAereaController(ILineaAereaService lineaAereaService) {
        this.lineaAereaService = lineaAereaService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<LineaAereaResponse>>> findAll(
            @RequestParam(required = false) Integer estado) {
        return ResponseEntity.ok(ApiResponse.success(lineaAereaService.findAll(estado)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<LineaAereaResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(lineaAereaService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ApiResponse<LineaAereaResponse>> create(@Valid @RequestBody LineaAereaRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(lineaAereaService.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ApiResponse<LineaAereaResponse>> update(@PathVariable Long id,
                                                                  @Valid @RequestBody LineaAereaRequest request) {
        return ResponseEntity.ok(ApiResponse.success(lineaAereaService.update(id, request)));
    }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ApiResponse<Void>> changeEstado(@PathVariable Long id, @RequestParam Integer estado) {
        lineaAereaService.changeEstado(id, estado);
        return ResponseEntity.ok(ApiResponse.success("Estado actualizado", null));
    }

    @PostMapping(value = "/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ApiResponse<LineaAereaResponse>> subirLogo(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) throws IOException {
        LineaAereaResponse response = lineaAereaService.subirLogo(id, file.getBytes(), file.getContentType());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * URL firmada temporal (15 min) para mostrar el logo en el frontend.
     * Abierta a cualquier usuario autenticado, igual que findAll/findById —
     * es solo lectura de un asset visual, no información sensible.
     */
    @GetMapping("/{id}/logo")
    public ResponseEntity<ApiResponse<String>> obtenerUrlLogo(@PathVariable Long id) {
        String url = lineaAereaService.obtenerUrlLogo(id);
        return ResponseEntity.ok(ApiResponse.success(url));
    }
}