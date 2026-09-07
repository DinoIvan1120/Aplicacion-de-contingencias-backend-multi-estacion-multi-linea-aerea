package com.saasa.contingencias.domain.dto.response;

import java.util.List;

/**
 * Previsualización de lo que la carga masiva de restaurante VA A PROCESAR,
 * sin crear absolutamente nada — se calcula con las mismas reglas de
 * parseo/agrupación/validación que {@code cargarRestauranteDesdeExcel}, para
 * que lo que el agente ve en el modal de confirmación (antes de firmar)
 * coincida exactamente con lo que el backend va a ejecutar al confirmar.
 */
public record CargaMasivaPreviewResponse(
        int totalPasajeros,
        int totalGrupos,
        List<GrupoPreview> grupos,
        List<String> erroresValidacion,
        Integer capacidadDisponible,
        int totalPaxSolicitado,
        boolean excedeCapacidad
) {

    public record GrupoPreview(
            String pnr,
            String nombreTitular,
            String correoTitular,
            String celularTitular,
            int integrantes,
            List<String> nombresIntegrantes,
            Integer paxRestaurante,
            boolean desayuno,
            boolean almuerzo,
            boolean cena
    ) {}
}
