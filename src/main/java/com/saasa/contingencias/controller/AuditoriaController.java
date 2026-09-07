package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.dto.response.AuditoriaResponse;
import com.saasa.contingencias.domain.model.Auditoria;
import com.saasa.contingencias.service.IAuditoriaService;
import com.saasa.contingencias.util.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * OPCIÓN 2: CONTROLLER COMPLETO
 *
 * ✅ Retorna información enriquecida (usuarioNombre, usuarioRol, etc.)
 * ✅ Múltiples endpoints para diferentes filtros
 * ✅ Más flexible para el frontend
 */
@RestController
@RequestMapping("/api/v1/auditoria")
@Tag(name = "Auditoría", description = "Endpoints para gestión de auditoría del sistema")
public class AuditoriaController {

    private final IAuditoriaService auditoriaService;

    public AuditoriaController(IAuditoriaService auditoriaService) {
        this.auditoriaService = auditoriaService;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ENDPOINT 1: Listar todas las auditorías (con filtro opcional por usuario)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * ✅ ACTUALIZADO: Retorna AuditoriaResponse con información enriquecida
     *
     * GET /api/v1/auditoria
     * GET /api/v1/auditoria?usuarioId=1
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(
            summary = "Listar auditorías",
            description = "Lista todas las auditorías del sistema con filtro opcional por usuario"
    )
    public ResponseEntity<ApiResponse<Page<AuditoriaResponse>>> findAll(
            @Parameter(description = "ID del usuario para filtrar (opcional)")
            @RequestParam(required = false) Long usuarioId,

            @PageableDefault(size = 20, sort = "creadoEn", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        Page<AuditoriaResponse> auditorias = usuarioId != null
                ? auditoriaService.buscarPorUsuario(usuarioId, pageable)
                : auditoriaService.listar(pageable);

        return ResponseEntity.ok(ApiResponse.success(auditorias));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ENDPOINT 2: Buscar por entidad específica
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * ✅ NUEVO: Ver todas las acciones sobre una entidad específica
     *
     * GET /api/v1/auditoria/entidad?tipo=Atencion&id=1
     *
     * Ejemplo: Ver todas las acciones sobre la atención "SGC-001"
     */
    @GetMapping("/entidad")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(
            summary = "Buscar auditorías por entidad",
            description = "Lista todas las acciones realizadas sobre una entidad específica (Atencion, Usuario, Proveedor, etc.)"
    )
    public ResponseEntity<ApiResponse<Page<AuditoriaResponse>>> buscarPorEntidad(
            @Parameter(description = "Tipo de entidad", example = "Atencion")
            @RequestParam String tipo,

            @Parameter(description = "ID de la entidad", example = "1")
            @RequestParam Long id,

            @PageableDefault(size = 20, sort = "creadoEn", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        Page<AuditoriaResponse> auditorias = auditoriaService.buscarPorEntidad(tipo, id, pageable);
        return ResponseEntity.ok(ApiResponse.success(auditorias));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ENDPOINT 3: Buscar por usuario específico
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * ✅ NUEVO: Ver todas las acciones de un usuario
     *
     * GET /api/v1/auditoria/usuario/1
     *
     * Ejemplo: Ver todas las acciones del usuario Juan Pérez
     */
    @GetMapping("/usuario/{usuarioId}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(
            summary = "Buscar auditorías por usuario",
            description = "Lista todas las acciones realizadas por un usuario específico"
    )
    public ResponseEntity<ApiResponse<Page<AuditoriaResponse>>> buscarPorUsuario(
            @Parameter(description = "ID del usuario", example = "1")
            @PathVariable Long usuarioId,

            @PageableDefault(size = 20, sort = "creadoEn", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        Page<AuditoriaResponse> auditorias = auditoriaService.buscarPorUsuario(usuarioId, pageable);
        return ResponseEntity.ok(ApiResponse.success(auditorias));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ENDPOINT 4: Exportar Excel (NUEVO)
    // ═════════════════════════════════════════════════════════════════════════

    @GetMapping("/excel")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(
            summary = "Exportar auditoría a Excel",
            description = "Exporta los registros de auditoría filtrados a un archivo .xlsx. " +
                    "Acepta los mismos filtros que el listado. Máximo 10.000 registros."
    )
    public ResponseEntity<byte[]> exportarExcel(
            @Parameter(description = "Texto libre")
            @RequestParam(required = false) String buscar,

            @Parameter(description = "ID del usuario")
            @RequestParam(required = false) Long usuarioId,

            @Parameter(description = "Módulo")
            @RequestParam(required = false) String modulo,

            @Parameter(description = "Acción")
            @RequestParam(required = false) String accion,

            @Parameter(description = "Fecha desde (yyyy-MM-dd)")
            @RequestParam(required = false) LocalDate fechaDesde,

            @Parameter(description = "Fecha hasta (yyyy-MM-dd)")
            @RequestParam(required = false) LocalDate fechaHasta
    ) {
        byte[] excel = auditoriaService.exportarExcel(
                buscar, usuarioId, modulo, accion, fechaDesde, fechaHasta);

        String filename = "auditoria-" + LocalDate.now() + ".xlsx";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel);
    }
}
