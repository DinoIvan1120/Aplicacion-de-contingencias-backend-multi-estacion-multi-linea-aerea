package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.enumeration.EstadoDetalleLoteEnum;
import com.saasa.contingencias.domain.enumeration.EstadoLoteEnum;
import com.saasa.contingencias.domain.model.CargaMasivaDetalle;
import com.saasa.contingencias.domain.model.CargaMasivaLote;
import com.saasa.contingencias.domain.repository.CargaMasivaDetalleRepository;
import com.saasa.contingencias.domain.repository.CargaMasivaLoteRepository;
import com.saasa.contingencias.service.ICargaMasivaProgresoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class CargaMasivaProgresoServiceImpl implements ICargaMasivaProgresoService {

    private final CargaMasivaLoteRepository loteRepository;
    private final CargaMasivaDetalleRepository detalleRepository;

    public CargaMasivaProgresoServiceImpl(CargaMasivaLoteRepository loteRepository,
                                          CargaMasivaDetalleRepository detalleRepository) {
        this.loteRepository = loteRepository;
        this.detalleRepository = detalleRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public CargaMasivaLote getLotePorLoteId(String loteId) {
        return loteRepository.findByLoteId(loteId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Lote no encontrado: " + loteId));
    }

    @Override
    @Transactional
    public void marcarDetalleEnviado(Long loteDbId, List<Long> detalleIds) {
        actualizarDetalles(detalleIds, EstadoDetalleLoteEnum.ENVIADO, null);
        actualizarContadores(loteDbId, detalleIds.size(), true);
    }

    @Override
    @Transactional
    public void marcarDetalleError(Long loteDbId, List<Long> detalleIds, String mensajeError) {
        actualizarDetalles(detalleIds, EstadoDetalleLoteEnum.ERROR, mensajeError);
        actualizarContadores(loteDbId, detalleIds.size(), false);
    }

    @Override
    @Transactional
    public void finalizarLote(Long loteDbId) {
        CargaMasivaLote lote = loteRepository.findById(loteDbId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Lote no encontrado: " + loteDbId));
        if (lote.getFallidos() == 0) {
            lote.setEstado(EstadoLoteEnum.COMPLETADO);
        } else if (lote.getExitosos() > 0) {
            lote.setEstado(EstadoLoteEnum.COMPLETADO_CON_ERRORES);
        } else {
            lote.setEstado(EstadoLoteEnum.ERROR);
        }
        loteRepository.save(lote);
    }

    @Override
    @Transactional
    public void marcarAtencionCreada(Long loteDbId, Long detalleId, Long atencionId, String correlativo) {
        CargaMasivaDetalle detalle = detalleRepository.findById(detalleId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Detalle de lote no encontrado: " + detalleId));
        detalle.setAtencionId(atencionId);
        detalle.setCorrelativo(correlativo);
        detalle.setEstado(EstadoDetalleLoteEnum.PENDIENTE);
        detalle.setMensajeError(null);
        detalleRepository.save(detalle);
        actualizarContadores(loteDbId, 1, true);
    }

    @Override
    @Transactional
    public void marcarErrorCreacionAtencion(Long loteDbId, Long detalleId, String mensajeError) {
        CargaMasivaDetalle detalle = detalleRepository.findById(detalleId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Detalle de lote no encontrado: " + detalleId));
        detalle.setEstado(EstadoDetalleLoteEnum.ERROR_CREACION);
        detalle.setMensajeError(mensajeError);
        detalleRepository.save(detalle);
        actualizarContadores(loteDbId, 1, false);
    }

    @Override
    @Transactional
    public void marcarAtencionesCreadasBatch(Long loteDbId, List<AtencionCreadaInfo> creadas) {
        if (creadas == null || creadas.isEmpty()) return;

        List<Long> ids = creadas.stream().map(AtencionCreadaInfo::detalleId).toList();
        Map<Long, AtencionCreadaInfo> porId = creadas.stream()
                .collect(java.util.stream.Collectors.toMap(AtencionCreadaInfo::detalleId, c -> c));

        List<CargaMasivaDetalle> detalles = detalleRepository.findAllById(ids);
        for (CargaMasivaDetalle d : detalles) {
            AtencionCreadaInfo info = porId.get(d.getId());
            d.setAtencionId(info.atencionId());
            d.setCorrelativo(info.correlativo());
            d.setEstado(EstadoDetalleLoteEnum.PENDIENTE);
            d.setMensajeError(null);
        }
        detalleRepository.saveAll(detalles);
        actualizarContadores(loteDbId, creadas.size(), true);
    }

    @Override
    @Transactional
    public void marcarErroresCreacionBatch(Long loteDbId, List<ErrorCreacionInfo> errores) {
        if (errores == null || errores.isEmpty()) return;

        List<Long> ids = errores.stream().map(ErrorCreacionInfo::detalleId).toList();
        Map<Long, ErrorCreacionInfo> porId = errores.stream()
                .collect(java.util.stream.Collectors.toMap(ErrorCreacionInfo::detalleId, e -> e));

        List<CargaMasivaDetalle> detalles = detalleRepository.findAllById(ids);
        for (CargaMasivaDetalle d : detalles) {
            ErrorCreacionInfo info = porId.get(d.getId());
            d.setEstado(EstadoDetalleLoteEnum.ERROR_CREACION);
            d.setMensajeError(info.mensajeError());
        }
        detalleRepository.saveAll(detalles);
        actualizarContadores(loteDbId, errores.size(), false);
    }

    @Override
    @Transactional
    public void finalizarFaseCreacion(Long loteDbId) {
        CargaMasivaLote lote = loteRepository.findById(loteDbId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Lote no encontrado: " + loteDbId));
        if (lote.getExitosos() == 0) {
            lote.setEstado(EstadoLoteEnum.ERROR_CREACION);
        } else {
            lote.setEstado(EstadoLoteEnum.PROCESANDO);
            lote.setProcesados(0);
            lote.setExitosos(0);
            lote.setFallidos(0);
        }
        loteRepository.save(lote);
    }

    private void actualizarDetalles(List<Long> ids, EstadoDetalleLoteEnum estado, String mensaje) {
        List<CargaMasivaDetalle> rows = detalleRepository.findAllById(ids);
        for (CargaMasivaDetalle d : rows) {
            d.setEstado(estado);
            d.setMensajeError(mensaje);
        }
        detalleRepository.saveAll(rows);
    }

    private void actualizarContadores(Long loteDbId, int cantidad, boolean exito) {
        CargaMasivaLote lote = loteRepository.findById(loteDbId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Lote no encontrado: " + loteDbId));
        lote.setProcesados(lote.getProcesados() + cantidad);
        if (exito) {
            lote.setExitosos(lote.getExitosos() + cantidad);
        } else {
            lote.setFallidos(lote.getFallidos() + cantidad);
        }
        loteRepository.save(lote);
    }
}
