package com.saasa.contingencias.domain.dto.response;

import java.util.List;

/**
 * Respuesta INMEDIATA de la carga masiva de pasajeros: el Excel ya se
 * validó y agrupó, y el lote quedó guardado listo para procesar — pero
 * TODAVÍA no se creó ninguna Atención (eso ocurre en background, ver
 * AtencionCargaMasivaCreadorAsyncImpl) ni se envió ningún voucher
 * (VoucherLoteOrchestratorImpl, después de la fase anterior).
 *
 * El frontend debe consultar el progreso de ambas fases con
 * {@code loteId} contra GET /atenciones/carga-masiva/lotes/{loteId}.
 */
public record CargaMasivaAtencionResponse(
        String loteId,
        int totalPasajeros,
        int totalGrupos,
        List<String> erroresValidacion
) {
    public boolean tieneErroresValidacion() {
        return erroresValidacion != null && !erroresValidacion.isEmpty();
    }
}
