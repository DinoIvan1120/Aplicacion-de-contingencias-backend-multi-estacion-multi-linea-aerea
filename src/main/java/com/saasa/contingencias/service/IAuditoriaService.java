package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.response.AuditoriaResponse;
import com.saasa.contingencias.domain.model.Auditoria;
import org.springframework.data.domain.*;

/**
 * Servicio de auditoría.
 *
 * ✅ MÉTODOS:
 * - registrar: Registrar acciones del sistema
 * - findAll: Listar con filtros opcionales
 * - listar: Listar con response enriquecido
 * - buscarPorUsuario: Filtrar por usuario
 * - buscarPorEntidad: Filtrar por entidad afectada
 */
public interface IAuditoriaService {

    /**
     * ✅ MÉTODO SIMPLE (compatibilidad con código existente)
     * Registrar acción básica sin información de entidad
     */
    void registrar(Long usuarioId, String accion, String modulo, String ipOrigen, Object detalle);

    /**
     * ✅ MÉTODO COMPLETO (nuevo)
     * Registrar acción con información completa de entidad
     */
    void registrar(
            Long usuarioId,
            String accion,
            String modulo,
            String detalle,
            String entidadTipo,
            Long entidadId,
            String entidadNombre
    );

    /**
     * ✅ MÉTODO EXISTENTE (mantener compatibilidad)
     * Buscar auditorías con filtro opcional por usuario
     */
    Page<Auditoria> findAll(Pageable pageable, Long usuarioId);

    /**
     * ✅ MÉTODO NUEVO
     * Listar con response enriquecido (incluye nombre de usuario, rol, etc.)
     */
    Page<AuditoriaResponse> listar(Pageable pageable);

    /**
     * ✅ MÉTODO NUEVO
     * Buscar por usuario específico
     */
    Page<AuditoriaResponse> buscarPorUsuario(Long usuarioId, Pageable pageable);

    /**
     * ✅ MÉTODO NUEVO
     * Buscar por entidad afectada
     */
    Page<AuditoriaResponse> buscarPorEntidad(
            String entidadTipo,
            Long entidadId,
            Pageable pageable
    );

    /**
     * ✅ NUEVO: Exporta los registros de auditoría filtrados a Excel (.xlsx).
     * Acepta los mismos filtros que el endpoint GET /auditoria/buscar.
     * Máximo 10.000 registros por exportación.
     */
    /**
     * ✅ NUEVO: Búsqueda combinada con múltiples filtros opcionales (Specification).
     */
    Page<AuditoriaResponse> buscarConFiltros(
            String buscar,
            Long usuarioId,
            String modulo,
            String accion,
            java.time.LocalDate fechaDesde,
            java.time.LocalDate fechaHasta,
            org.springframework.data.domain.Pageable pageable
    );

    byte[] exportarExcel(
            String buscar,
            Long usuarioId,
            String modulo,
            String accion,
            java.time.LocalDate fechaDesde,
            java.time.LocalDate fechaHasta
    );
}
