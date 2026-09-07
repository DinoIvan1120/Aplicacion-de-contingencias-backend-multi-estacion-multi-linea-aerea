package com.saasa.contingencias.domain.dto.response;

/**
 * Response con los datos extraídos del boarding pass
 */
public record BoardingPassScanResponse(
        String nombreCompleto,
        String pnr,
        String codigoVuelo,
        String origen,
        String destino,
        String clase,
        String asiento,
        String fechaVuelo,
        String horaAbordaje,
        boolean nombreTruncado
) {}
