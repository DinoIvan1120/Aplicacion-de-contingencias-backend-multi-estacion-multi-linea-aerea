package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.RegistroVueloDiario;
import com.saasa.contingencias.domain.model.Usuario;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RegistroVueloDiarioRepository extends JpaRepository<RegistroVueloDiario, Long> {

    /**
     * Encuentra todos los registros activos de una fecha específica.
     * Usado por agentes para ver solo los vuelos del día.
     */
    @Query("SELECT r FROM RegistroVueloDiario r " +
            "WHERE r.fechaRegistro = :fecha " +
            "AND r.active = true " +
            "ORDER BY r.registradoEn ASC")
    List<RegistroVueloDiario> findByFechaRegistroAndActivoTrue(@Param("fecha") LocalDate fecha);

    /**
     * Encuentra registros activos dentro de un rango de fechas, acotados a
     * la estación+línea aérea del contexto activo.
     * Usado por líderes para ver su historial.
     *
     * El filtro estación/línea va en el WHERE (no en memoria después de
     * paginar) para que Page.getTotalElements() refleje el total real que
     * cumple la condición y la paginación no pierda registros entre páginas.
     */
    @Query("SELECT r FROM RegistroVueloDiario r " +
            "WHERE r.fechaRegistro BETWEEN :fechaInicio AND :fechaFin " +
            "AND r.active = true " +
            "AND r.estacionId = :estacionId " +
            "AND (:lineaAereaId IS NULL OR r.lineaAereaId = :lineaAereaId) " +
            "ORDER BY r.fechaRegistro DESC, r.registradoEn DESC")
    Page<RegistroVueloDiario> findByFechaRegistroBetweenAndActivoTrue(
            @Param("fechaInicio") LocalDate fechaInicio,
            @Param("fechaFin") LocalDate fechaFin,
            @Param("estacionId") Long estacionId,
            @Param("lineaAereaId") Long lineaAereaId,
            Pageable pageable);

    /**
     * Encuentra registros activos de un líder específico, acotados a la
     * estación+línea aérea del contexto activo (ver nota de arriba sobre
     * por qué el filtro va en el WHERE y no en memoria).
     */
    @Query("SELECT r FROM RegistroVueloDiario r " +
            "WHERE r.registradoPor = :lider " +
            "AND r.active = true " +
            "AND r.estacionId = :estacionId " +
            "AND (:lineaAereaId IS NULL OR r.lineaAereaId = :lineaAereaId) " +
            "ORDER BY r.fechaRegistro DESC, r.registradoEn DESC")
    Page<RegistroVueloDiario> findByRegistradoPorAndActivoTrue(
            @Param("lider") Usuario lider,
            @Param("estacionId") Long estacionId,
            @Param("lineaAereaId") Long lineaAereaId,
            Pageable pageable);

    /**
     * Encuentra registros activos de un líder en un rango de fechas,
     * acotados a la estación+línea aérea del contexto activo.
     */
    @Query("SELECT r FROM RegistroVueloDiario r " +
            "WHERE r.registradoPor = :lider " +
            "AND r.fechaRegistro BETWEEN :fechaInicio AND :fechaFin " +
            "AND r.active = true " +
            "AND r.estacionId = :estacionId " +
            "AND (:lineaAereaId IS NULL OR r.lineaAereaId = :lineaAereaId) " +
            "ORDER BY r.fechaRegistro DESC, r.registradoEn DESC")
    Page<RegistroVueloDiario> findByRegistradoPorAndFechaRegistroBetween(
            @Param("lider") Usuario lider,
            @Param("fechaInicio") LocalDate fechaInicio,
            @Param("fechaFin") LocalDate fechaFin,
            @Param("estacionId") Long estacionId,
            @Param("lineaAereaId") Long lineaAereaId,
            Pageable pageable);

    /**
     * Verifica si un vuelo ya fue registrado en una fecha específica (evitar duplicados).
     */
    @Query("SELECT COUNT(r) > 0 FROM RegistroVueloDiario r " +
            "WHERE r.vueloItinerario.id = :vueloId " +
            "AND r.fechaRegistro = :fecha " +
            "AND r.active = true")
    boolean existsByVueloItinerarioIdAndFechaRegistroAndActivoTrue(
            @Param("vueloId") Long vueloId,
            @Param("fecha") LocalDate fecha);

    /**
     * Encuentra un registro específico por vuelo y fecha.
     */
    @Query("SELECT r FROM RegistroVueloDiario r " +
            "WHERE r.vueloItinerario.id = :vueloId " +
            "AND r.fechaRegistro = :fecha " +
            "AND r.active = true")
    Optional<RegistroVueloDiario> findByVueloItinerarioIdAndFechaRegistroAndActivoTrue(
            @Param("vueloId") Long vueloId,
            @Param("fecha") LocalDate fecha);

    /**
     * Cuenta registros activos de un día específico.
     * Útil para estadísticas del dashboard.
     */
    @Query("SELECT COUNT(r) FROM RegistroVueloDiario r " +
            "WHERE r.fechaRegistro = :fecha " +
            "AND r.active = true")
    long countByFechaRegistroAndActivoTrue(@Param("fecha") LocalDate fecha);

    /**
     * Encuentra todos los registros activos (paginado), acotados a la
     * estación+línea aérea del contexto activo.
     * Para vista de administrador.
     */
    @Query("SELECT r FROM RegistroVueloDiario r " +
            "WHERE r.active = true " +
            "AND r.estacionId = :estacionId " +
            "AND (:lineaAereaId IS NULL OR r.lineaAereaId = :lineaAereaId) " +
            "ORDER BY r.fechaRegistro DESC, r.registradoEn DESC")
    Page<RegistroVueloDiario> findAllByActivoTrue(
            @Param("estacionId") Long estacionId,
            @Param("lineaAereaId") Long lineaAereaId,
            Pageable pageable);

    /**
     * Encuentra registros activos en un rango de fechas, sin paginar,
     * acotados a la estación+línea aérea del contexto activo.
     * Usado para extender la ventana del agente a 24h/48h.
     */
    @Query("SELECT r FROM RegistroVueloDiario r " +
            "WHERE r.fechaRegistro BETWEEN :fechaInicio AND :fechaFin " +
            "AND r.active = true " +
            "AND r.estacionId = :estacionId " +
            "AND (:lineaAereaId IS NULL OR r.lineaAereaId = :lineaAereaId) " +
            "ORDER BY r.registradoEn ASC")
    List<RegistroVueloDiario> findByFechaRegistroEntreYActivoTrueList(
            @Param("fechaInicio") LocalDate fechaInicio,
            @Param("fechaFin") LocalDate fechaFin,
            @Param("estacionId") Long estacionId,
            @Param("lineaAereaId") Long lineaAereaId);
}
