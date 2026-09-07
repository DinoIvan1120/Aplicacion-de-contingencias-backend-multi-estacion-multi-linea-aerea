package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.IconoModo;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface IconoModoRepository extends JpaRepository<IconoModo, Long> {
    Optional<IconoModo> findByClave(String clave);
}