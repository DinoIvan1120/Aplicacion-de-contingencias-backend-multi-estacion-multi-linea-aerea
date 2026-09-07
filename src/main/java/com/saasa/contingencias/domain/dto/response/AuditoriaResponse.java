package com.saasa.contingencias.domain.dto.response;

import java.time.LocalDateTime;

/**
 * Response mejorado para el endpoint de auditoría.
 *
 * ✅ MEJORAS:
 * - Incluye información del usuario (nombre, rol)
 * - Incluye información de la entidad afectada
 * - Mapeo completo para el frontend
 */
public record AuditoriaResponse(
        Long id,

        // ✅ Información del usuario
        Long usuarioId,
        String usuarioNombre,      // ✅ NUEVO: Nombre completo del usuario
        String usuarioRol,          // ✅ NUEVO: Rol del usuario que ejecutó la acción

        // ✅ Información de la acción
        String accion,              // Ej: CREAR_ATENCION, ACTUALIZAR_SERVICIOS
        String modulo,              // Ej: ATENCIONES, USUARIOS, PROVEEDORES

        // ✅ Información de contexto
        String ipOrigen,            // IP desde donde se realizó la acción
        String detalle,             // JSON con detalles de la operación

        // ✅ Información de la entidad afectada (NUEVO)
        String entidadTipo,         // Ej: Atencion, Usuario, Proveedor
        Long entidadId,             // ID de la entidad afectada
        String entidadNombre,       // Nombre/correlativo de la entidad

        // ✅ Timestamp
        LocalDateTime creadoEn
) {
    /**
     * Constructor simplificado para compatibilidad con datos existentes
     */
    public AuditoriaResponse(
            Long id,
            Long usuarioId,
            String accion,
            String modulo,
            String ipOrigen,
            String detalle,
            LocalDateTime creadoEn
    ) {
        this(
                id,
                usuarioId,
                null,  // usuarioNombre - se llenará en el servicio
                null,  // usuarioRol - se llenará en el servicio
                accion,
                modulo,
                ipOrigen,
                detalle,
                null,  // entidadTipo
                null,  // entidadId
                null,  // entidadNombre
                creadoEn
        );
    }
}

