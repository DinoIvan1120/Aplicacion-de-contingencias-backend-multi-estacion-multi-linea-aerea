package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.Estacion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface EstacionRepository extends JpaRepository<Estacion, Long> {
    Optional<Estacion> findByCodigoIata(String codigoIata);
    boolean existsByCodigoIata(String codigoIata);
}
