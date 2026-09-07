package com.saasa.contingencias.domain.dto.response;

/**
 * Línea aérea habilitada dentro de una estación — usada por el selector
 * dependiente del login (GET /estaciones/{id}/lineas-aereas, sección 5.1)
 * y por el módulo de administración de Estaciones/Líneas Aéreas (5.2).
 *
 * totalVuelos: cantidad de vuelos ACTIVOS registrados por esa aerolínea en
 * esa estación (se muestra como badge "N vuelos" en el selector).
 */
public record EstacionLineaAereaResponse(
        Long id, Long lineaAereaId, String lineaAereaCodigoIata, String lineaAereaNombre,
        Integer estado, long totalVuelos
) {}
