package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.VueloRecurso;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface VueloRecursoRepository extends JpaRepository<VueloRecurso, Long> {
    List<VueloRecurso> findByVueloIdAndEstado(Long vueloId, Integer estado);
    boolean existsByVueloIdAndProveedorId(Long vueloId, Long proveedorId);

    /**
     * Igual que findById, pero toma un bloqueo pesimista (SELECT ... FOR UPDATE)
     * sobre la fila del recurso hasta que termine la transacción.
     *
     * USO EXCLUSIVO: validación + asignación de servicios (asignarServicios en
     * AtencionServiceImpl), donde varios agentes pueden intentar reservar el
     * mismo hotel/transporte/restaurante casi al mismo tiempo. Con este lock,
     * la segunda transacción que pida el mismo recurso espera (milisegundos)
     * a que la primera confirme o revierta, en vez de leer un conteo de
     * "habitaciones usadas" desactualizado y aprobar una reserva que ya no
     * cabe (sobreventa).
     *
     * NO usar este método para lecturas normales (edición de recursos,
     * reportes, disponibilidad en pantalla): esas siguen usando el
     * findById() estándar de JpaRepository, que no bloquea nada.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT vr FROM VueloRecurso vr WHERE vr.id = :id")
    Optional<VueloRecurso> findByIdForUpdate(@Param("id") Long id);

    /**
     * Busca un recurso por vuelo y proveedor (activo o inactivo).
     * Usado en el lote para hacer upsert en lugar de crear duplicados.
     */
    @Query("""
        SELECT vr FROM VueloRecurso vr
        WHERE vr.vuelo.id = :vueloId
          AND vr.proveedor.id = :proveedorId
        """)
    Optional<VueloRecurso> findByVueloIdAndProveedorIdOptional(
            @Param("vueloId") Long vueloId,
            @Param("proveedorId") Long proveedorId
    );

    /**
     * Devuelve TODOS los VueloRecurso activos de una fecha específica
     * (todos los vuelos de ese día), agrupados por proveedor.
     *
     * Se usa para calcular cuánta capacidad ya está comprometida hoy
     * por cada proveedor, sin importar a qué vuelo está asignada.
     *
     * Cuando el líder abre el combo de proveedores para un nuevo vuelo,
     * se llama una sola vez y se obtiene el resumen completo del día
     * en lugar de hacer N consultas (una por proveedor).
     *
     * El registroId que se excluye (excludeRegistroId) sirve para el
     * caso de EDICIÓN: no contabilizar los recursos del propio registro
     * que se está editando, para que aparezcan como disponibles.
     */
    @Query("""
        SELECT vr FROM VueloRecurso vr
        WHERE vr.registroVueloDiario.fechaRegistro = :fecha
          AND vr.estado = 1
          AND (:excludeRegistroId IS NULL
               OR vr.registroVueloDiario.id <> :excludeRegistroId)
        """)
    List<VueloRecurso> findActivosPorFechaExcluyendoRegistro(
            @Param("fecha") LocalDate fecha,
            @Param("excludeRegistroId") Long excludeRegistroId
    );

    /**
     * Igual que findActivosPorFechaExcluyendoRegistro, pero para un RANGO
     * de fechas (hoy + mañana), usado cuando la ventana del agente
     * está habilitada por 24h/48h en vez de un solo día.
     */
    @Query("""
    SELECT vr FROM VueloRecurso vr
    WHERE vr.registroVueloDiario.fechaRegistro BETWEEN :fechaInicio AND :fechaFin
      AND vr.estado = 1
      AND (:excludeRegistroId IS NULL
           OR vr.registroVueloDiario.id <> :excludeRegistroId)
    """)
    List<VueloRecurso> findActivosPorRangoFechaExcluyendoRegistro(
            @Param("fechaInicio") LocalDate fechaInicio,
            @Param("fechaFin") LocalDate fechaFin,
            @Param("excludeRegistroId") Long excludeRegistroId
    );

}
