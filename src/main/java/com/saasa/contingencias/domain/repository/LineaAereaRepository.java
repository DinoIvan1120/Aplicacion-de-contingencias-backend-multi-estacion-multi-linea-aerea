package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.LineaAerea;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface LineaAereaRepository extends JpaRepository<LineaAerea, Long> {
    Optional<LineaAerea> findByCodigoIata(String codigoIata);
    Optional<LineaAerea> findByNombreIgnoreCase(String nombre);
    boolean existsByCodigoIata(String codigoIata);
}
