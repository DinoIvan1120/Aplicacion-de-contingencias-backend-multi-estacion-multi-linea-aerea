package com.saasa.contingencias.domain.dto.response;

import java.util.List;

/**
 * Respuesta de la carga masiva de vuelos desde Excel.
 *
 * Devuelve dos listas separadas:
 *  - {@code registrados}  → vuelos que se crearon correctamente.
 *  - {@code errores}      → descripción de cada fila que falló y el motivo.
 *
 * De este modo el frontend puede:
 *  1. Mostrar cuántos vuelos se importaron.
 *  2. Mostrar un modal con los errores sin necesidad de relanzar toda la carga.
 *
 * Reemplaza el {@code List<VueloResponse>} anterior que silenciaba los errores.
 */
public record CargaMasivaResponse(
        List<VueloResponse> registrados,
        List<String>        errores
) {
    /** Atajos de consulta usados en el controller y en el frontend. */
    public int totalRegistrados() { return registrados != null ? registrados.size() : 0; }
    public int totalErrores()     { return errores     != null ? errores.size()     : 0; }
    public boolean tieneErrores() { return totalErrores() > 0; }
}
