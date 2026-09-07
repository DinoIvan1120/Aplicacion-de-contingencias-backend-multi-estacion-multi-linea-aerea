package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.dto.request.AerolineaCorreoRequest;
import com.saasa.contingencias.domain.dto.response.AerolineaCorreoResponse;
import com.saasa.contingencias.service.IAerolineaCorreoService;
import com.saasa.contingencias.util.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * NUEVO — Parametrización de correos de aerolíneas (administrador).
 * Estos correos se usan para completar automáticamente los destinatarios
 * (CC) del voucher que se envía al pasajero, según la aerolínea del vuelo.
 */
@RestController
@RequestMapping("/api/v1/aerolineas-correo")
@Tag(name = "Correos de Aerolíneas")
public class AerolineaCorreoController {

    private final IAerolineaCorreoService service;

    public AerolineaCorreoController(IAerolineaCorreoService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Lista los correos de aerolíneas parametrizados (estado: 1=activos, 0=inactivos)")
    public ResponseEntity<ApiResponse<Page<AerolineaCorreoResponse>>> findAll(
            @RequestParam(required = false) Integer estado,
            @PageableDefault(size = 50, sort = "aerolinea") Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(service.findAll(estado, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    public ResponseEntity<ApiResponse<AerolineaCorreoResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ApiResponse<AerolineaCorreoResponse>> create(
            @Valid @RequestBody AerolineaCorreoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Correo de aerolínea creado exitosamente", service.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ApiResponse<AerolineaCorreoResponse>> update(
            @PathVariable Long id, @Valid @RequestBody AerolineaCorreoRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Correo de aerolínea actualizado", service.update(id, request)));
    }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ApiResponse<Void>> cambiarEstado(
            @PathVariable Long id, @RequestParam Integer estado) {
        service.cambiarEstado(id, estado);
        return ResponseEntity.ok(ApiResponse.success("Estado actualizado", null));
    }
}

