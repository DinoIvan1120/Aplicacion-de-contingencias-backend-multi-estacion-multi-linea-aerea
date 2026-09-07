package com.saasa.contingencias.service;

/**
 * Orquesta en BACKGROUND la creación de las Atenciones (y la asignación
 * del servicio de restaurante por grupo) de un lote de carga masiva,
 * fila por fila, actualizando el progreso del lote para que pueda
 * consultarse por polling desde el frontend — igual patrón que
 * IVoucherLoteOrchestrator, pero para la fase 1 (antes de que existan
 * las Atenciones) en vez de la fase 2 (envío de vouchers).
 *
 * Separado de IAtencionCargaMasivaService a propósito: el parseo del
 * Excel y el guardado inicial del lote son síncronos y transaccionales
 * (rápidos); la creación de las Atenciones es asíncrona y se dispara
 * SOLO después de que esa transacción ya hizo commit (ver
 * AtencionCargaMasivaController), para evitar procesar un lote que el
 * hilo en background todavía no puede ver.
 */
public interface IAtencionCargaMasivaCreador {
    /**
     * @param loteId    UUID público del lote (CargaMasivaLote.loteId)
     * @param usuarioId Agente que originó la carga (para auditoría de cada Atención creada)
     */
    void crearAtencionesAsync(String loteId, Long usuarioId);
}
