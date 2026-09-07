package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.LineaAereaRequest;
import com.saasa.contingencias.domain.dto.response.LineaAereaResponse;

import java.util.List;

public interface ILineaAereaService {
    List<LineaAereaResponse> findAll(Integer estado);
    LineaAereaResponse findById(Long id);
    LineaAereaResponse create(LineaAereaRequest request);
    LineaAereaResponse update(Long id, LineaAereaRequest request);
    void changeEstado(Long id, Integer estado);

    LineaAereaResponse subirLogo(Long id, byte[] logoBytes, String contentType);

    /**
     * URL firmada temporal (15 min) para mostrar el logo de la aerolínea.
     * Null si la aerolínea aún no tiene logo cargado.
     */
    String obtenerUrlLogo(Long id);
}
