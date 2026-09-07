package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.Auditoria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Repositorio de auditoría.
 *
 * ✅ MÉTODOS AGREGADOS:
 * - findByEntidadTipoAndEntidadId: Buscar por entidad afectada
 * - findByAccion: Buscar por tipo de acción
 * - findByModulo: Buscar por módulo
 */
@Repository
public interface AuditoriaRepository extends JpaRepository<Auditoria, Long>, JpaSpecificationExecutor<Auditoria> {

    /**
     * ✅ EXISTENTE
     * Buscar auditorías de un usuario específico
     */
    Page<Auditoria> findByUsuarioId(Long usuarioId, Pageable pageable);

    /**
     * ✅ NUEVO
     * Buscar auditorías de una entidad específica
     * Ejemplo: Ver todas las acciones sobre la atención "SGC-001"
     */
    Page<Auditoria> findByEntidadTipoAndEntidadId(
            String entidadTipo,
            Long entidadId,
            Pageable pageable
    );

    /**
     * ✅ NUEVO
     * Buscar por tipo de acción
     * Ejemplo: Ver todas las acciones "CREAR_ATENCION"
     */
    Page<Auditoria> findByAccion(String accion, Pageable pageable);

    /**
     * ✅ NUEVO
     * Buscar por módulo
     * Ejemplo: Ver todas las acciones en el módulo "ATENCIONES"
     */
    Page<Auditoria> findByModulo(String modulo, Pageable pageable);
}
