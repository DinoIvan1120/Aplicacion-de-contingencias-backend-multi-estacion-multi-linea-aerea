package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.BoardingPassScanRequest;
import com.saasa.contingencias.domain.dto.response.BoardingPassScanResponse;

/**
 * Servicio para decodificar códigos de barras de boarding pass
 */
public interface IBoardingPassService {

    /**
     * Decodifica el código de barras del boarding pass y extrae la información
     *
     * @param request Request con el código de barras
     * @return Datos extraídos del boarding pass
     */
    BoardingPassScanResponse escanearBoardingPass(BoardingPassScanRequest request);
}

