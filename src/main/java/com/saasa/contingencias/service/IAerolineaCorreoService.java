package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.AerolineaCorreoRequest;
import com.saasa.contingencias.domain.dto.response.AerolineaCorreoResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface IAerolineaCorreoService {
    Page<AerolineaCorreoResponse> findAll(Integer estado, Pageable pageable);
    AerolineaCorreoResponse findById(Long id);
    AerolineaCorreoResponse create(AerolineaCorreoRequest request);
    AerolineaCorreoResponse update(Long id, AerolineaCorreoRequest request);
    void cambiarEstado(Long id, Integer nuevoEstado);

    /**
     * ACTUALIZADO — Busca el correo parametrizado para un par
     * estación+línea aérea (antes buscaba por nombre de aerolínea en
     * texto libre, sin distinguir estación). Null si no existe o está
     * inactivo para ese par.
     */
    String buscarCorreoPorContexto(Long estacionId, Long lineaAereaId);
}
