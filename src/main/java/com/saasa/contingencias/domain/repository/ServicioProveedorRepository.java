package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.ServicioProveedor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ServicioProveedorRepository extends JpaRepository<ServicioProveedor, Long> {
    /** Todos los servicios activos de un proveedor. */
    List<ServicioProveedor> findByProveedorIdAndEstado(Long proveedorId, Integer estado);

    /** Todos los servicios (activos e inactivos) de un proveedor. */
    List<ServicioProveedor> findByProveedorId(Long proveedorId);

    /**
     * Busca un servicio por proveedor y tipo (activo o inactivo).
     * Usado en el upsert de actualización: si ya existe (aunque inactivo)
     * actualiza su monto y lo reactiva, evitando crear duplicados.
     */
    @Query("""
        SELECT sp FROM ServicioProveedor sp
        WHERE sp.proveedor.id = :proveedorId
          AND sp.tipoServicio = :tipoServicio
        ORDER BY sp.id ASC
        """)
    Optional<ServicioProveedor> findByProveedorIdAndTipoServicio(
            @Param("proveedorId") Long proveedorId,
            @Param("tipoServicio") String tipoServicio
    );
}
