package com.saasa.contingencias.controller;

import com.saasa.contingencias.service.IIconoModoService;
import com.saasa.contingencias.util.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Íconos configurables de la pantalla "Seleccionar Modo" del Administrador
 * Global (Gestionar Estaciones y Aerolíneas / Operar en una estación).
 * Mismo patrón que la foto de {@link EstacionController} — GET abierto a
 * cualquier autenticado (solo lectura de un asset visual), POST reservado
 * al Administrador Global.
 */
@RestController
@RequestMapping("/api/v1/config/iconos-modo")
@Tag(name = "Íconos de modo")
public class IconoModoController {

    private final IIconoModoService iconoModoService;

    public IconoModoController(IIconoModoService iconoModoService) {
        this.iconoModoService = iconoModoService;
    }

    @GetMapping("/{clave}")
    public ResponseEntity<ApiResponse<String>> obtenerUrl(@PathVariable String clave) {
        return ResponseEntity.ok(ApiResponse.success(iconoModoService.obtenerUrlIcono(clave)));
    }

    @PostMapping(value = "/{clave}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMINISTRADOR') and @estacionContext.esAdministradorGlobal()")
    public ResponseEntity<ApiResponse<String>> subirIcono(
            @PathVariable String clave,
            @RequestParam("file") MultipartFile file) throws IOException {
        String url = iconoModoService.subirIcono(clave, file.getBytes(), file.getContentType());
        return ResponseEntity.ok(ApiResponse.success(url));
    }
}