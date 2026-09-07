package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.Vuelo;
import com.saasa.contingencias.domain.enumeration.EstadoVueloEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.util.List;

public interface VueloRepository extends JpaRepository<Vuelo, Long>, JpaSpecificationExecutor<Vuelo> {
    Page<Vuelo> findByEstado(EstadoVueloEnum estado, Pageable pageable);
    boolean existsByIdAndEstado(Long id, EstadoVueloEnum estado);

    /**
     * Verifica si ya existe un vuelo activo con el mismo código y fecha.
     * Usado en create() y crearRegistroCompleto() para impedir duplicados.
     * La unicidad real es: codigoVuelo + fechaVuelo (un vuelo es único por número y día).
     */
    boolean existsByCodigoVueloAndFechaVuelo(String codigoVuelo, LocalDate fechaVuelo);

    /**
     * Igual que el anterior pero excluye el propio registro al actualizar,
     * evitando falsos positivos en update() cuando no cambia código ni fecha.
     */
    boolean existsByCodigoVueloAndFechaVueloAndIdNot(String codigoVuelo, LocalDate fechaVuelo, Long id);

    /**
     * Devuelve todos los vuelos ACTIVOS cuya fechaVuelo sea exactamente la fecha indicada.
     * Usado por el endpoint GET /vuelos/itinerario-hoy para que el líder solo vea
     * los vuelos del día actual — ni de ayer ni de días futuros.
     * La consulta usa el índice compuesto uq_vuelo_codigo_fecha (codigoVuelo, fechaVuelo)
     * y el índice idx_vuelo_estado, por lo que es O(log n) aunque la tabla sea grande.
     */
    List<Vuelo> findByFechaVueloAndEstadoOrderByCodigoVueloAsc(
            LocalDate fechaVuelo, EstadoVueloEnum estado);

    /**
     * Cuenta los vuelos ACTIVOS registrados para una línea aérea dentro de
     * una estación. Usado en el selector de aerolínea post-login (Fase 5)
     * para mostrar cuántos vuelos lleva registrados cada aerolínea.
     */
    long countByEstacionIdAndLineaAereaIdAndEstado(
            Long estacionId, Long lineaAereaId, EstadoVueloEnum estado);
}
