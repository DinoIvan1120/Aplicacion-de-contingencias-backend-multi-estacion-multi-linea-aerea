package com.saasa.contingencias.service;

/**
 * Orquesta en BACKGROUND el envío de vouchers (PDF + email + WhatsApp) de
 * todos los pasajeros/grupos de un lote de carga masiva, actualizando el
 * progreso del lote y de cada fila a medida que avanza para que pueda
 * consultarse por polling desde el frontend.
 *
 * Separado de IAtencionCargaMasivaService a propósito: la creación de
 * atenciones es síncrona y transaccional; el envío de vouchers es
 * asíncrono y se dispara SOLO después de que esa transacción ya hizo
 * commit (ver AtencionCargaMasivaController), para evitar procesar
 * atenciones que el hilo en background todavía no puede ver.
 */
public interface IVoucherLoteOrchestrator {

    /**
     * @param loteId    UUID público del lote (CargaMasivaLote.loteId)
     * @param usuarioId Agente que originó la carga (para auditoría de cada envío)
     */
    void procesarLoteAsync(String loteId, Long usuarioId);
}