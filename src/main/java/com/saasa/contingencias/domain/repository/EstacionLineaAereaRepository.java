package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.EstacionLineaAerea;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface EstacionLineaAereaRepository extends JpaRepository<EstacionLineaAerea, Long> {
    Optional<EstacionLineaAerea> findByEstacionIdAndLineaAereaId(Long estacionId, Long lineaAereaId);
    List<EstacionLineaAerea> findByEstacionId(Long estacionId);
}
