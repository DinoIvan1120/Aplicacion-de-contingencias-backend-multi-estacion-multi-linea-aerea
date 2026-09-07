package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.domain.dto.request.AtencionRequest;
import com.saasa.contingencias.domain.dto.request.ServicioAsignadoRequest;
import com.saasa.contingencias.domain.dto.response.AtencionResponse;
import com.saasa.contingencias.domain.enumeration.EstadoLoteEnum;
import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import com.saasa.contingencias.domain.model.CargaMasivaDetalle;
import com.saasa.contingencias.domain.model.CargaMasivaLote;
import com.saasa.contingencias.domain.repository.CargaMasivaDetalleRepository;
import com.saasa.contingencias.service.IAtencionCargaMasivaCreador;
import com.saasa.contingencias.service.IAtencionService;
import com.saasa.contingencias.service.ICargaMasivaProgresoService;
import com.saasa.contingencias.service.IVoucherLoteOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AtencionCargaMasivaCreadorAsyncImpl implements IAtencionCargaMasivaCreador {

    private static final Logger log = LoggerFactory.getLogger(AtencionCargaMasivaCreadorAsyncImpl.class);

    private final ICargaMasivaProgresoService progresoService;
    private final CargaMasivaDetalleRepository detalleRepository;
    private final IAtencionService atencionService;
    private final IVoucherLoteOrchestrator voucherLoteOrchestrator;

    public AtencionCargaMasivaCreadorAsyncImpl(ICargaMasivaProgresoService progresoService,
                                               CargaMasivaDetalleRepository detalleRepository,
                                               IAtencionService atencionService,
                                               IVoucherLoteOrchestrator voucherLoteOrchestrator) {
        this.progresoService = progresoService;
        this.detalleRepository = detalleRepository;
        this.atencionService = atencionService;
        this.voucherLoteOrchestrator = voucherLoteOrchestrator;
    }

    @Override
    @Async("cargaMasivaExecutor")
    public void crearAtencionesAsync(String loteId, Long usuarioId) {
        CargaMasivaLote lote;
        try {
            lote = progresoService.getLotePorLoteId(loteId);
        } catch (Exception e) {
            log.error("[CargaMasivaCreador] Lote {} no encontrado — se aborta la creación", loteId);
            return;
        }

        try {
            List<CargaMasivaDetalle> detalles = detalleRepository.findByLote_IdOrderByIdAsc(lote.getId());
            log.info("[CargaMasivaCreador] Iniciando creación de {} pasajero(s) del lote {}",
                    detalles.size(), loteId);

            Long vueloId = lote.getVuelo().getId();
            Long registroVueloDiarioId = lote.getRegistroVueloDiario().getId();
            Long vueloRecursoId = lote.getVueloRecurso().getId();

            Map<String, List<CargaMasivaDetalle>> grupos = new LinkedHashMap<>();
            for (CargaMasivaDetalle d : detalles) {
                String clave = d.getGrupoId() != null ? d.getGrupoId() : ("__individual__" + d.getId());
                grupos.computeIfAbsent(clave, k -> new ArrayList<>()).add(d);
            }

            List<ICargaMasivaProgresoService.AtencionCreadaInfo> bufferCreadas = new ArrayList<>();
            List<ICargaMasivaProgresoService.ErrorCreacionInfo> bufferErrores = new ArrayList<>();

            for (List<CargaMasivaDetalle> grupo : grupos.values()) {
                procesarGrupo(grupo, vueloId, registroVueloDiarioId, vueloRecursoId, usuarioId,
                        bufferCreadas, bufferErrores);

                if (bufferCreadas.size() + bufferErrores.size() >= CHUNK_SIZE) {
                    flush(lote.getId(), bufferCreadas, bufferErrores);
                }
            }
            flush(lote.getId(), bufferCreadas, bufferErrores);
        } catch (Exception e) {
            log.error("[CargaMasivaCreador] Error crítico creando atenciones del lote {}", loteId, e);
        } finally {
            progresoService.finalizarFaseCreacion(lote.getId());

            CargaMasivaLote actualizado = progresoService.getLotePorLoteId(loteId);
            if (actualizado.getEstado() == EstadoLoteEnum.PROCESANDO) {
                log.info("[CargaMasivaCreador] Lote {} — fase de creación terminada, disparando envío de vouchers",
                        loteId);
                voucherLoteOrchestrator.procesarLoteAsync(loteId, usuarioId);
            } else {
                log.warn("[CargaMasivaCreador] Lote {} terminó sin ninguna atención creada (estado {})",
                        loteId, actualizado.getEstado());
            }
        }
    }

    private static final int CHUNK_SIZE = 25;

    private void procesarGrupo(List<CargaMasivaDetalle> grupo,
                               Long vueloId, Long registroVueloDiarioId, Long vueloRecursoId, Long usuarioId,
                               List<ICargaMasivaProgresoService.AtencionCreadaInfo> bufferCreadas,
                               List<ICargaMasivaProgresoService.ErrorCreacionInfo> bufferErrores) {

        Map<Long, AtencionResponse> creadasPorFilaId = new LinkedHashMap<>();
        List<CargaMasivaDetalle> filasCreadasOk = new ArrayList<>();

        for (CargaMasivaDetalle fila : grupo) {
            try {
                AtencionRequest req = new AtencionRequest(
                        fila.getNombre(), fila.getApellido(), fila.getPnr(),
                        fila.getCorreo(), fila.getCelular(),
                        vueloId, registroVueloDiarioId,
                        null, null, null,
                        fila.getGrupoId(), null);

                AtencionResponse creada = atencionService.create(req, usuarioId);
                creadasPorFilaId.put(fila.getId(), creada);
                filasCreadasOk.add(fila);
            } catch (Exception ex) {
                log.warn("[CargaMasivaCreador] Error creando atención — fila {} (PNR {}): {}",
                        fila.getId(), fila.getPnr(), ex.getMessage());
                bufferErrores.add(new ICargaMasivaProgresoService.ErrorCreacionInfo(
                        fila.getId(), truncar(ex.getMessage())));
            }
        }

        if (filasCreadasOk.isEmpty()) {
            return;
        }

        CargaMasivaDetalle titularDatos = grupo.stream()
                .filter(d -> Boolean.TRUE.equals(d.getEsTitular()))
                .findFirst()
                .orElse(grupo.get(0));
        CargaMasivaDetalle filaDestino = filasCreadasOk.contains(titularDatos)
                ? titularDatos
                : filasCreadasOk.get(0);
        Long atencionIdDestino = creadasPorFilaId.get(filaDestino.getId()).id();

        String errorAsignacion = null;
        try {
            ServicioAsignadoRequest svRequest = new ServicioAsignadoRequest(
                    vueloRecursoId,
                    TipoDetalleEnum.RESTAURANTE,
                    null,
                    titularDatos.getDesayuno(), titularDatos.getAlmuerzo(), titularDatos.getCena(),
                    false,
                    null, null, null,
                    titularDatos.getPaxRestaurante() != null ? titularDatos.getPaxRestaurante() : 1);

            atencionService.asignarServicios(atencionIdDestino, List.of(svRequest), usuarioId);
        } catch (Exception ex) {
            errorAsignacion = ex.getMessage();
            log.warn("[CargaMasivaCreador] Atención(es) creadas pero falló la asignación del servicio " +
                    "— PNR titular {}: {}", titularDatos.getPnr(), ex.getMessage());
        }

        for (CargaMasivaDetalle fila : filasCreadasOk) {
            AtencionResponse creada = creadasPorFilaId.get(fila.getId());
            if (errorAsignacion == null) {
                bufferCreadas.add(new ICargaMasivaProgresoService.AtencionCreadaInfo(
                        fila.getId(), creada.id(), creada.numeroCorrelativo()));
            } else {
                bufferErrores.add(new ICargaMasivaProgresoService.ErrorCreacionInfo(fila.getId(),
                        truncar("Atención creada pero falló la asignación del servicio: " + errorAsignacion)));
            }
        }
    }

    private void flush(Long loteDbId,
                       List<ICargaMasivaProgresoService.AtencionCreadaInfo> bufferCreadas,
                       List<ICargaMasivaProgresoService.ErrorCreacionInfo> bufferErrores) {
        if (!bufferCreadas.isEmpty()) {
            progresoService.marcarAtencionesCreadasBatch(loteDbId, new ArrayList<>(bufferCreadas));
            bufferCreadas.clear();
        }
        if (!bufferErrores.isEmpty()) {
            progresoService.marcarErroresCreacionBatch(loteDbId, new ArrayList<>(bufferErrores));
            bufferErrores.clear();
        }
    }

    private String truncar(String mensaje) {
        if (mensaje == null || mensaje.isBlank()) return "Error desconocido al crear la atención";
        return mensaje.length() > 490 ? mensaje.substring(0, 490) : mensaje;
    }
}
