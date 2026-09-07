package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.AerolineaCorreo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface AerolineaCorreoRepository
        extends JpaRepository<AerolineaCorreo, Long>, JpaSpecificationExecutor<AerolineaCorreo> {

    Page<AerolineaCorreo> findByEstado(Integer estado, Pageable pageable);

    // ACTUALIZADO — La unicidad ya no es por nombre de aerolínea (texto
    // libre), sino por el par estación+línea aérea: la misma aerolínea
    // puede tener un correo distinto por cada estación donde opera.
    boolean existsByEstacionIdAndLineaAereaId(Long estacionId, Long lineaAereaId);

    boolean existsByEstacionIdAndLineaAereaIdAndIdNot(Long estacionId, Long lineaAereaId, Long id);

    /** Usado por el flujo del agente para autocompletar el correo de la aerolínea del vuelo. */
    Optional<AerolineaCorreo> findFirstByEstacionIdAndLineaAereaIdAndEstado(
            Long estacionId, Long lineaAereaId, Integer estado);
}
