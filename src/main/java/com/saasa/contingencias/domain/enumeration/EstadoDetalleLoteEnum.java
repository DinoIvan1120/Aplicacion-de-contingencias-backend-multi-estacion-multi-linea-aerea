package com.saasa.contingencias.domain.enumeration;

/** Estado de una fila (pasajero) dentro de un lote de carga masiva. */
public enum EstadoDetalleLoteEnum {
    POR_CREAR,        // Fila válida del Excel, la Atención aún no se ha creado
    ERROR_CREACION,   // Falló la creación de la Atención (o la asignación del servicio) para esta fila
    PENDIENTE,  // Atención ya creada, voucher aún no procesado
    ENVIADO,    // Voucher generado y enviado (email / WhatsApp) correctamente
    ERROR       // Falló la generación o el envío del voucher
}
