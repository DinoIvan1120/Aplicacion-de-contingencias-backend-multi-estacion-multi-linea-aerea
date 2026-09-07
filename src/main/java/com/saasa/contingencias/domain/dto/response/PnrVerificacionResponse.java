package com.saasa.contingencias.domain.dto.response;

/**
 * Respuesta de la verificación de PNR duplicado antes del escaneo de boarding pass.
 *
 * @param duplicado        true si el PNR ya tiene una atención activa para el vuelo
 * @param pnr              PNR consultado
 * @param nombrePasajero   Nombre completo del pasajero ya registrado (null si no duplicado)
 * @param correlativo      Número de correlativo de la atención existente (null si no duplicado)
 */
public record PnrVerificacionResponse(
        boolean duplicado,
        String pnr,
        String nombrePasajero,
        String correlativo
) {}
