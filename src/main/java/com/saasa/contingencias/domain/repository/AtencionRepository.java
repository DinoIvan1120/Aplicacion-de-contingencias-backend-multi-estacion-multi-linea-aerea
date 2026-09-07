package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.enumeration.EstadoAtencionEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AtencionRepository extends JpaRepository<Atencion, Long>, JpaSpecificationExecutor<Atencion> {

    boolean existsByPnrAndVueloIdAndEstado(String pnr, Long vueloId, EstadoAtencionEnum estado);
    boolean existsByVueloId(Long vueloId);

    /**
     * Busca una atención por PNR y vueloId para detectar duplicados al escanear.
     * Se usa en la validación previa al registro desde el boarding pass.
     */
    @Query("""
        SELECT a FROM Atencion a
        WHERE UPPER(a.pnr) = UPPER(:pnr)
          AND a.vuelo.id = :vueloId
          AND a.estado <> com.saasa.contingencias.domain.enumeration.EstadoAtencionEnum.ANULADO
    """)
    Optional<Atencion> findByPnrAndVueloId(@Param("pnr") String pnr, @Param("vueloId") Long vueloId);



    Page<Atencion> findByVueloId(Long vueloId, Pageable pageable);

    /**
     * MEJORA 1 — Obtiene el número secuencial máximo actual desde BD.
     * Permite que el correlativo sea persistente y no se reinicie con el server.
     * Extrae el número del formato "SGC-NNNNNNNNN" → devuelve NNNNNNNNN como Long.
     */
    @Query("""
        SELECT COALESCE(MAX(CAST(SUBSTRING(a.numeroCorrelativo, 5) AS long)), 999)
        FROM Atencion a
    """)
    Long findMaxCorrelativoNumber();

    /**
     * MEJORA 2 — Filtro por vuelo para LINEA_AEREA.
     * Retorna solo atenciones de vuelos cuya aerolínea coincide con el usuario.
     */
    @Query("""
        SELECT a FROM Atencion a
        JOIN a.vuelo v
        JOIN a.atendidoPor u
        WHERE v.aerolinea = :aerolinea
    """)
    Page<Atencion> findByAerolinea(@Param("aerolinea") String aerolinea, Pageable pageable);

    /**
     * MEJORA 3 — Filtro por proveedor para rol PROVEEDOR.
     * Retorna solo atenciones que tienen servicios asignados del proveedor indicado.
     */
    @Query("""
        SELECT DISTINCT a FROM Atencion a
        JOIN ServicioAsignado sa ON sa.atencion = a
        JOIN sa.vueloRecurso sp
        WHERE sp.proveedor.id = :proveedorId
    """)
    Page<Atencion> findByProveedorId(@Param("proveedorId") Long proveedorId, Pageable pageable);

    @Query("""
    SELECT COUNT(a) > 0 FROM Atencion a
    JOIN ServicioAsignado sa ON sa.atencion = a
    JOIN sa.vueloRecurso vr
    WHERE vr.proveedor.id = :proveedorId AND a.id = :atencionId
    """)
    boolean existsByProveedorId(@Param("atencionId") Long atencionId, @Param("proveedorId") Long proveedorId);

    /**
     * Busca todas las atenciones asociadas a un registro diario específico
     */
    @Query("""
        SELECT a FROM Atencion a
        WHERE a.registroVueloDiario.id = :registroVueloDiarioId
        ORDER BY a.createdAt DESC
    """)
    java.util.List<Atencion> findByRegistroVueloDiarioId(@Param("registroVueloDiarioId") Long registroVueloDiarioId);

    /**
     * Busca una atención por su número de correlativo.
     *
     * @param numeroCorrelativo Número de correlativo (ej: "SGC-000001000")
     * @return Atención si existe
     */
    Optional<Atencion> findByNumeroCorrelativo(String numeroCorrelativo);

    /**
     * NUEVO — Voucher grupal: obtiene todas las atenciones (pasajeros) que
     * comparten el mismo grupoId. Se usa en reportes para listar a los
     * demás pasajeros incluidos en el mismo voucher PDF.
     */
    List<Atencion> findByGrupoIdOrderByIdAsc(String grupoId);

    /**
     * NUEVO — Voucher grupal: id del pasajero TITULAR del grupo (el más
     * antiguo). Se usa para que la lista de reportes muestre el monto total
     * solo en el titular y 0 en los demás, evitando que se vea el mismo
     * total repetido en cada pasajero del grupo.
     */
    @Query(
            "SELECT MIN(a.id) FROM Atencion a WHERE a.grupoId = :grupoId")
    Long findMinIdByGrupoId(@Param("grupoId") String grupoId);

    /**
     * NUEVO — Voucher grupal: atención TITULAR completa (la más antigua) de
     * un grupo. Se usa en la lista de reportes para saber si la fila actual
     * ES el titular y para mostrar el nombre del titular como identificador
     * del grupo en las demás filas.
     */
    @Query("""
        SELECT a FROM Atencion a
        WHERE a.id = (SELECT MIN(a2.id) FROM Atencion a2 WHERE a2.grupoId = :grupoId)
        """)
    Optional<Atencion> findTitularByGrupoId(@Param("grupoId") String grupoId);

    // 1. Total de atenciones activas en un rango de fechas
    @Query(value = """
    SELECT COUNT(*) FROM atenciones
    WHERE created_at >= :inicio AND created_at <= :fin
      AND estado != 'ANULADO'
      AND estacion_id = :estacionId AND (:lineaAereaId IS NULL OR linea_aerea_id = :lineaAereaId)
    """, nativeQuery = true)
    Long countActivosByFechaRange(
            @Param("inicio") LocalDateTime inicio,
            @Param("fin")   LocalDateTime fin,
            @Param("estacionId") Long estacionId,
            @Param("lineaAereaId") Long lineaAereaId);

    // 2. Suma del monto_total de atenciones activas en el rango
    // FIX: en voucher grupal (PNR/correo compartido) el monto_total se
    // propaga a TODOS los integrantes del grupo (para que el detalle de
    // cada uno lo muestre completo). Sumarlo tal cual aquí duplicaba el
    // ingreso real por cada pasajero del grupo. Se suma solo la fila
    // TITULAR de cada grupo (grupo_id NULL = pasajero independiente, o
    // id = MIN(id) de su grupo_id).
    @Query(value = """
    SELECT COALESCE(SUM(monto_total), 0) FROM atenciones a
    WHERE created_at >= :inicio AND created_at <= :fin
      AND estado != 'ANULADO'
      AND (grupo_id IS NULL OR id = (
            SELECT MIN(a2.id) FROM atenciones a2 WHERE a2.grupo_id = a.grupo_id
          ))
      AND estacion_id = :estacionId AND (:lineaAereaId IS NULL OR linea_aerea_id = :lineaAereaId)
    """, nativeQuery = true)
    BigDecimal sumMontoByFechaRange(
            @Param("inicio") LocalDateTime inicio,
            @Param("fin")   LocalDateTime fin,
            @Param("estacionId") Long estacionId,
            @Param("lineaAereaId") Long lineaAereaId);

    // 3. Atenciones agrupadas por día — Object[0]=fecha(String), Object[1]=cantidad(Long)
    @Query(value = """
    SELECT DATE(created_at)         AS fecha,
           COUNT(*)                 AS cantidad
    FROM atenciones
    WHERE created_at >= :inicio AND created_at <= :fin
      AND estado != 'ANULADO'
      AND estacion_id = :estacionId AND (:lineaAereaId IS NULL OR linea_aerea_id = :lineaAereaId)
    GROUP BY DATE(created_at)
    ORDER BY fecha
    """, nativeQuery = true)
    List<Object[]> countByFecha(
            @Param("inicio") LocalDateTime inicio,
            @Param("fin")   LocalDateTime fin,
            @Param("estacionId") Long estacionId,
            @Param("lineaAereaId") Long lineaAereaId);

    // 4. Importe agrupado por día — Object[0]=fecha(String), Object[1]=importe(BigDecimal)
    // FIX: mismo criterio que sumMontoByFechaRange — solo cuenta el monto
    // del pasajero TITULAR de cada grupo, para no multiplicar el importe
    // real por la cantidad de pasajeros de un voucher grupal.
    @Query(value = """
    SELECT DATE(created_at)               AS fecha,
           COALESCE(SUM(monto_total), 0)  AS importe
    FROM atenciones a
    WHERE created_at >= :inicio AND created_at <= :fin
      AND estado != 'ANULADO'
      AND (grupo_id IS NULL OR id = (
            SELECT MIN(a2.id) FROM atenciones a2 WHERE a2.grupo_id = a.grupo_id
          ))
      AND estacion_id = :estacionId AND (:lineaAereaId IS NULL OR linea_aerea_id = :lineaAereaId)
    GROUP BY DATE(created_at)
    ORDER BY fecha
    """, nativeQuery = true)
    List<Object[]> importeByFecha(
            @Param("inicio") LocalDateTime inicio,
            @Param("fin")   LocalDateTime fin,
            @Param("estacionId") Long estacionId,
            @Param("lineaAereaId") Long lineaAereaId);

    // 5. Distribución por tipo de servicio
//    Object[0]=tipo(String), Object[1]=cantidad(Long), Object[2]=importe(BigDecimal)
    @Query(value = """
    SELECT sa.tipo_detalle                          AS tipo,
           COUNT(DISTINCT sa.atencion_id)           AS cantidad,
           COALESCE(SUM(sa.monto_subtotal), 0)      AS importe
    FROM servicios_asignados sa
    JOIN atenciones a ON sa.atencion_id = a.id
    WHERE a.created_at >= :inicio AND a.created_at <= :fin
      AND a.estado != 'ANULADO'
      AND a.estacion_id = :estacionId AND (:lineaAereaId IS NULL OR a.linea_aerea_id = :lineaAereaId)
    GROUP BY sa.tipo_detalle
    """, nativeQuery = true)
    List<Object[]> distribucionByTipo(
            @Param("inicio") LocalDateTime inicio,
            @Param("fin")   LocalDateTime fin,
            @Param("estacionId") Long estacionId,
            @Param("lineaAereaId") Long lineaAereaId);

    // 6. Distribución por proveedor individual
    //    Object[0]=tipo(String), Object[1]=nombreProveedor(String),
    //    Object[2]=cantidad(Long),  Object[3]=importe(BigDecimal)
    @Query(value = """
    SELECT p.tipo                                   AS tipo,
           p.nombre                                 AS nombre,
           COUNT(DISTINCT sa.atencion_id)           AS cantidad,
           COALESCE(SUM(sa.monto_subtotal), 0)      AS importe
    FROM servicios_asignados sa
    JOIN atenciones a  ON sa.atencion_id = a.id
    JOIN vuelo_recursos vr ON sa.vuelo_recurso_id = vr.id
    JOIN proveedores p     ON vr.proveedor_id     = p.id
    WHERE a.created_at >= :inicio AND a.created_at <= :fin
      AND a.estado != 'ANULADO'
      AND a.estacion_id = :estacionId AND (:lineaAereaId IS NULL OR a.linea_aerea_id = :lineaAereaId)
    GROUP BY p.tipo, p.id, p.nombre
    ORDER BY p.tipo, importe DESC
    """, nativeQuery = true)
    List<Object[]> distribucionByProveedor(
            @Param("inicio") LocalDateTime inicio,
            @Param("fin")   LocalDateTime fin,
            @Param("estacionId") Long estacionId,
            @Param("lineaAereaId") Long lineaAereaId);
}