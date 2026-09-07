package com.saasa.contingencias.domain.dto.response;

import java.util.List;

/**
 * Estado consultable (polling) de un lote de envío masivo de vouchers.
 * El frontend lo consulta cada pocos segundos mientras estado == "PROCESANDO"
 * para mostrar una barra de progreso "X de Y enviados".
 */
public record LoteEstadoResponse(
        String loteId,
        // CREANDO_ATENCIONES | ERROR_CREACION | PROCESANDO | COMPLETADO |
        // COMPLETADO_CON_ERRORES | ERROR
        String estado,
        int totalPasajeros,
        int procesados,
        int exitosos,
        int fallidos,
        List<DetalleItem> detalle
) {
    public record DetalleItem(
            String correlativo,
            String pnr,
            String nombreCompleto,
            boolean titular,
            // POR_CREAR | ERROR_CREACION | PENDIENTE | ENVIADO | ERROR
            String estado,
            String mensajeError
    ) {}
}
