package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request para escanear código de barras del boarding pass
 */
public record BoardingPassScanRequest(

        @NotBlank(message = "El código de barras es obligatorio")
        String codigoBarras

) {}