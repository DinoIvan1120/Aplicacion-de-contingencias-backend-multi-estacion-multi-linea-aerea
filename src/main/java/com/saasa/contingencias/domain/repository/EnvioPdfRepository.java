package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.EnvioPdf;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EnvioPdfRepository extends JpaRepository<EnvioPdf, Long> {
    List<EnvioPdf> findByAtencionId(Long atencionId);
}
