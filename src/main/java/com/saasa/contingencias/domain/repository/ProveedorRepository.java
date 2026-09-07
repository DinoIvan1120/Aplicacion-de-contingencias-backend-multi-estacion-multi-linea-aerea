package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import com.saasa.contingencias.domain.model.Proveedor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProveedorRepository extends JpaRepository<Proveedor, Long>, JpaSpecificationExecutor<Proveedor> {
    boolean existsByRuc(String ruc);
    Page<Proveedor> findByEstado(Integer estado, Pageable pageable);

    /** Filtra por tipo solamente. */
    Page<Proveedor> findByTipo(TipoProveedorEnum tipo, Pageable pageable);

    /** Filtra por tipo Y estado. */
    Page<Proveedor> findByTipoAndEstado(TipoProveedorEnum tipo, Integer estado, Pageable pageable);

    /**
     * MEJORA 3 — Busca el proveedor cuyo correo coincide con el del usuario autenticado.
     * Usado para filtrar atenciones visibles para el rol PROVEEDOR.
     */
    @Query("SELECT p FROM Proveedor p WHERE p.correo = :correoUsuario AND p.estado = 1")
    Optional<Proveedor> findActivoByCorreo(@Param("correoUsuario") String correoUsuario);

    /**
     * NUEVO — Busca proveedor activo por RUC.
     * Fallback en resolverProveedorId: el codigoEmpleado del usuario PROVEEDOR
     * puede contener el RUC del proveedor asociado.
     */
    Optional<Proveedor> findByRucAndEstado(String ruc, Integer estado);
}
