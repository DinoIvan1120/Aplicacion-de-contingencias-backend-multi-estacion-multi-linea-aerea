package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.CargaMasivaDetalle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CargaMasivaDetalleRepository extends JpaRepository<CargaMasivaDetalle, Long> {

    /** Todas las filas de un lote, en el orden en que fueron creadas. */
    List<CargaMasivaDetalle> findByLote_IdOrderByIdAsc(Long loteId);
}
