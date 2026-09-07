package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.model.CargaMasivaLote;

import java.util.List;

/**
 * Actualizaciones de progreso de un lote, aisladas en su propio bean
 * transaccional para que cada actualización se confirme (commit) de
 * inmediato en su propia transacción — así el polling del frontend ve
 * avances reales mientras VoucherLoteOrchestratorImpl sigue procesando
 * el resto del lote en el mismo hilo @Async.
 */
public interface ICargaMasivaProgresoService {

    CargaMasivaLote getLotePorLoteId(String loteId);

    void marcarDetalleEnviado(Long loteDbId, List<Long> detalleIds);

    void marcarDetalleError(Long loteDbId, List<Long> detalleIds, String mensajeError);

    void finalizarLote(Long loteDbId);

    // ── Fase 1: creación de atenciones (AtencionCargaMasivaCreadorAsyncImpl) ──

    /**
     * Marca una fila como creada exitosamente: guarda el {@code atencionId}
     * y {@code correlativo} obtenidos, deja la fila en PENDIENTE (lista
     * para la fase 2) y suma 1 a procesados/exitosos del lote.
     */

    void marcarAtencionCreada(Long loteDbId, Long detalleId, Long atencionId, String correlativo);

    /**
     * Marca una fila como fallida en la fase de creación (no se pudo crear
     * la Atención, o sí se creó pero falló la asignación del servicio):
     * deja la fila en ERROR_CREACION con el mensaje de error, y suma 1 a
     * procesados/fallidos del lote.
     */

    void marcarErrorCreacionAtencion(Long loteDbId, Long detalleId, String mensajeError);

    /**
     * Cierra la fase 1 para el lote indicado. Si se creó al menos una
     * fila (exitosos > 0), resetea procesados/exitosos/fallidos a 0 y
     * pasa el lote a PROCESANDO para que arranque la fase 2 (envío de
     * vouchers). Si ninguna fila se pudo crear, deja el lote en
     * ERROR_CREACION.
     */

    void marcarAtencionesCreadasBatch(Long loteDbId, List<AtencionCreadaInfo> creadas);

    void marcarErroresCreacionBatch(Long loteDbId, List<ErrorCreacionInfo> errores);

    record AtencionCreadaInfo(Long detalleId, Long atencionId, String correlativo) {}

    record ErrorCreacionInfo(Long detalleId, String mensajeError) {}

    void finalizarFaseCreacion(Long loteDbId);
}
