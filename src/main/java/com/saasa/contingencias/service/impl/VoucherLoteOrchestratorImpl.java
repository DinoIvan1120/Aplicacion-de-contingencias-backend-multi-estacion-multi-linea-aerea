package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.domain.dto.request.GenerarVoucherRequest;
import com.saasa.contingencias.domain.dto.request.VoucherGrupalRequest;
import com.saasa.contingencias.domain.enumeration.EstadoDetalleLoteEnum;
import com.saasa.contingencias.domain.model.CargaMasivaDetalle;
import com.saasa.contingencias.domain.model.CargaMasivaLote;
import com.saasa.contingencias.domain.repository.CargaMasivaDetalleRepository;
import com.saasa.contingencias.service.IAtencionVoucherService;
import com.saasa.contingencias.service.ICargaMasivaProgresoService;
import com.saasa.contingencias.service.IVoucherLoteOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Procesa el envío de vouchers de un lote en un hilo separado
 * (executor "cargaMasivaExecutor", ver AsyncConfig) para que la petición
 * HTTP de carga masiva no se quede esperando mientras se generan ~100
 * PDFs y se envían ~100 correos/WhatsApp.
 *
 * Cada fila (o grupo) se procesa y confirma de forma independiente vía
 * ICargaMasivaProgresoService — así, si una falla, el resto sigue su
 * curso y el frontend ve el progreso real actualizándose mientras dura
 * el proceso.
 */
@Service
public class VoucherLoteOrchestratorImpl implements IVoucherLoteOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(VoucherLoteOrchestratorImpl.class);

    private final CargaMasivaDetalleRepository detalleRepository;
    private final IAtencionVoucherService atencionVoucherService;
    private final ICargaMasivaProgresoService progresoService;

    public VoucherLoteOrchestratorImpl(CargaMasivaDetalleRepository detalleRepository,
                                       IAtencionVoucherService atencionVoucherService,
                                       ICargaMasivaProgresoService progresoService) {
        this.detalleRepository = detalleRepository;
        this.atencionVoucherService = atencionVoucherService;
        this.progresoService = progresoService;
    }

    @Override
    @Async("cargaMasivaExecutor")
    public void procesarLoteAsync(String loteId, Long usuarioId) {
        CargaMasivaLote lote;
        try {
            lote = progresoService.getLotePorLoteId(loteId);
        } catch (Exception e) {
            log.error("[CargaMasivaVoucher] Lote {} no encontrado — se aborta el procesamiento", loteId);
            return;
        }

        // TODO el cuerpo va envuelto en try/catch/finally: si algo revienta
        // ACÁ (fuera de los try individuales de cada pasajero/grupo — p.ej.
        // deserializarCcDestinos, una consulta a BD, o un cuelgue de SMTP/S3
        // sin timeout que termina lanzando excepción), antes NO se llamaba a
        // finalizarLote() y el lote quedaba huérfano en PROCESANDO para
        // siempre: el frontend hace polling infinito (refetchInterval solo
        // se detiene cuando estado deja de ser "PROCESANDO") y jamás llegan
        // los correos. El finally garantiza que el lote SIEMPRE termine en
        // un estado final, y el catch marca como ERROR cualquier fila que
        // se haya quedado PENDIENTE por la falla.
        try {
            List<CargaMasivaDetalle> detalles = detalleRepository.findByLote_IdOrderByIdAsc(lote.getId()).stream()
                    .filter(d -> d.getEstado() == EstadoDetalleLoteEnum.PENDIENTE)
                    .toList();
            // NOTA — las filas que se hayan quedado en ERROR_CREACION (no se
            // pudo crear su Atención, o falló la asignación del servicio en
            // AtencionCargaMasivaCreadorAsyncImpl) NO tienen atencionId y no
            // se procesan acá: ya quedaron marcadas como error en la fase 1,
            // y esta fase 2 solo debe tocar filas que sí llegaron a existir
            // como Atención.
            log.info("[CargaMasivaVoucher] Iniciando envío de {} pasajero(s) del lote {}", detalles.size(), loteId);

            // NUEVO — correos CC y firma de conformidad elegidos por el agente
            // en el modal previo a la carga masiva (mismo modal/lógica que
            // handleConfirmarFirmaYEnviar en la vista individual del agente),
            // se aplican a TODOS los vouchers del lote.
            List<String> ccDestinos = deserializarCcDestinos(lote.getCcDestinosJson());
            String firmaPasajero = lote.getFirmaPasajero();
            // NUEVO — rol real (ADMINISTRADOR | LIDER_SAASA | AGENTE_SAASA) de
            // quien cargó el Excel, tomado de lote.cargadoPor (ya se guarda al
            // crear el lote). Se propaga a cada voucher para que el reporte
            // muestre el rol correcto en vez de un texto fijo de "agente".
            String origenFirmaRol = lote.getCargadoPor() != null && lote.getCargadoPor().getRol() != null
                    ? lote.getCargadoPor().getRol().name()
                    : null;

            // Los que comparten grupoId van juntos (UN solo voucher grupal);
            // los que tienen grupoId == null son pasajeros individuales.
            Map<String, List<CargaMasivaDetalle>> grupos = new LinkedHashMap<>();
            List<CargaMasivaDetalle> individuales = new ArrayList<>();
            for (CargaMasivaDetalle d : detalles) {
                if (d.getGrupoId() != null) {
                    grupos.computeIfAbsent(d.getGrupoId(), k -> new ArrayList<>()).add(d);
                } else {
                    individuales.add(d);
                }
            }

            for (CargaMasivaDetalle d : individuales) {
                procesarIndividual(lote.getId(), d, ccDestinos, firmaPasajero, origenFirmaRol,usuarioId);
            }
            for (List<CargaMasivaDetalle> grupo : grupos.values()) {
                procesarGrupo(lote.getId(), grupo, ccDestinos, firmaPasajero,origenFirmaRol, usuarioId);
            }
        } catch (Exception e) {
            log.error("[CargaMasivaVoucher] Error crítico procesando el lote {} — se marcarán como error " +
                    "las filas que hayan quedado pendientes", loteId, e);
            marcarPendientesComoError(lote.getId(),
                    "Error interno al procesar el lote: " + truncar(e.getMessage()));
        } finally {
            progresoService.finalizarLote(lote.getId());
            log.info("[CargaMasivaVoucher] Lote {} finalizado", loteId);
        }
    }

    /**
     * Red de seguridad para el catch de arriba: marca como ERROR cualquier
     * fila que se haya quedado en PENDIENTE (no llegó a procesarse ni como
     * éxito ni como error individual) para que el lote pueda cerrar en
     * COMPLETADO_CON_ERRORES/ERROR en vez de quedar contando menos filas
     * de las que en realidad tiene.
     */
    private void marcarPendientesComoError(Long loteDbId, String mensaje) {
        List<CargaMasivaDetalle> pendientes = detalleRepository.findByLote_IdOrderByIdAsc(loteDbId).stream()
                .filter(d -> d.getEstado() == com.saasa.contingencias.domain.enumeration.EstadoDetalleLoteEnum.PENDIENTE)
                .toList();
        if (pendientes.isEmpty()) return;
        List<Long> ids = pendientes.stream().map(CargaMasivaDetalle::getId).toList();
        progresoService.marcarDetalleError(loteDbId, ids, mensaje);
    }

    private void procesarIndividual(Long loteDbId, CargaMasivaDetalle d,
                                    List<String> ccDestinos, String firmaPasajero,String origenFirmaRol, Long usuarioId) {
        try {
            GenerarVoucherRequest req = new GenerarVoucherRequest(
                    d.getCorreo(), ccDestinos, firmaPasajero, "ES", "AGENTE_LOTE", origenFirmaRol);
            atencionVoucherService.generarYEnviarVoucher(d.getAtencionId(), req, usuarioId);
            progresoService.marcarDetalleEnviado(loteDbId, List.of(d.getId()));
        } catch (Exception e) {
            log.warn("[CargaMasivaVoucher] Error enviando voucher — atencionId={}: {}",
                    d.getAtencionId(), e.getMessage());
            progresoService.marcarDetalleError(loteDbId, List.of(d.getId()), truncar(e.getMessage()));
        }
    }

    private void procesarGrupo(Long loteDbId, List<CargaMasivaDetalle> grupo,
                               List<String> ccDestinos, String firmaPasajero,String origenFirmaRol, Long usuarioId) {
        CargaMasivaDetalle titular = grupo.stream()
                .filter(CargaMasivaDetalle::getEsTitular)
                .findFirst()
                .orElse(grupo.get(0));
        List<Long> idsDetalle = grupo.stream().map(CargaMasivaDetalle::getId).toList();
        try {
            List<Long> atencionIds = grupo.stream().map(CargaMasivaDetalle::getAtencionId).toList();
            VoucherGrupalRequest req = new VoucherGrupalRequest(
                    atencionIds, titular.getCorreo(), true, ccDestinos, firmaPasajero, "ES","AGENTE_LOTE",origenFirmaRol);
            atencionVoucherService.generarYEnviarVoucherGrupal(req, usuarioId);
            progresoService.marcarDetalleEnviado(loteDbId, idsDetalle);
        } catch (Exception e) {
            log.warn("[CargaMasivaVoucher] Error enviando voucher grupal — PNR titular={}: {}",
                    titular.getPnr(), e.getMessage());
            progresoService.marcarDetalleError(loteDbId, idsDetalle, truncar(e.getMessage()));
        }
    }

    /**
     * NUEVO — inverso de AtencionCargaMasivaServiceImpl.serializarCcDestinos:
     * separa el string guardado en {@code lote.ccDestinosJson} por
     * {@link CargaMasivaLote#CC_SEPARATOR} en la lista de correos CC.
     * Devuelve {@code null} (no lista vacía) cuando el lote no tiene CC
     * configurados, para que GenerarVoucherRequest/VoucherGrupalRequest
     * se comporten igual que cuando no se envía ese campo.
     */
    private List<String> deserializarCcDestinos(String ccDestinosJson) {
        if (ccDestinosJson == null || ccDestinosJson.isBlank()) return null;
        List<String> correos = Arrays.stream(ccDestinosJson.split(CargaMasivaLote.CC_SEPARATOR))
                .map(String::trim)
                .filter(c -> !c.isEmpty())
                .toList();
        return correos.isEmpty() ? null : correos;
    }

    private String truncar(String mensaje) {
        if (mensaje == null || mensaje.isBlank()) return "Error desconocido al generar/enviar el voucher";
        return mensaje.length() > 490 ? mensaje.substring(0, 490) : mensaje;
    }
}
