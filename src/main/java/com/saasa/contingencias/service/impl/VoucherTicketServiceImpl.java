package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.exception.PdfGenerationException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.enumeration.EstadoAtencionEnum;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.model.ServicioAsignado;
import com.saasa.contingencias.domain.repository.AtencionRepository;
import com.saasa.contingencias.domain.repository.ServicioAsignadoRepository;
import com.saasa.contingencias.service.IVoucherTicketService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NUEVO — Arma los datos del voucher tipo ticket (individual o grupal) con
 * las MISMAS reglas que AtencionVoucherServiceImpl usa para el A4, y delega
 * el dibujo del PDF en TicketPdfRenderer.
 *
 * Solo lectura: no guarda nada en BD, no sube a S3 y no envía correos ni
 * WhatsApp. No modifica el flujo actual del voucher A4.
 */
@Service
public class VoucherTicketServiceImpl implements IVoucherTicketService {

    private final AtencionRepository atencionRepository;
    private final ServicioAsignadoRepository servicioAsignadoRepository;
    private final TicketPdfRenderer ticketPdfRenderer;

    public VoucherTicketServiceImpl(AtencionRepository atencionRepository,
                                    ServicioAsignadoRepository servicioAsignadoRepository,
                                    TicketPdfRenderer ticketPdfRenderer) {
        this.atencionRepository = atencionRepository;
        this.servicioAsignadoRepository = servicioAsignadoRepository;
        this.ticketPdfRenderer = ticketPdfRenderer;
    }

    @Override
    @Transactional(readOnly = true)
    public TicketPdf generarTicket(Long atencionId, int anchoMm) {
        if (anchoMm != ANCHO_80_MM && anchoMm != ANCHO_104_MM) {
            throw new BadRequestException("El ancho del ticket debe ser 80 o 104 mm");
        }

        Atencion atencion = atencionRepository.findById(atencionId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Atención no encontrada: " + atencionId));

        if (atencion.getEstado() == EstadoAtencionEnum.ANULADO) {
            throw new BadRequestException("No se puede imprimir el ticket de una atención anulada");
        }

        // ── ¿Pertenece a un voucher grupal? Mismo criterio que el reenvío del A4 ──
        List<Atencion> grupo = atencion.getGrupoId() != null
                ? atencionRepository.findByGrupoIdOrderByIdAsc(atencion.getGrupoId())
                : List.of();

        try {
            if (grupo.size() > 1) {
                return generarGrupal(grupo, anchoMm);
            }
            return generarIndividual(atencion, anchoMm);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfGenerationException("Error al generar el ticket del voucher para " + atencionId, e);
        }
    }

    private TicketPdf generarIndividual(Atencion atencion, int anchoMm) {
        List<ServicioAsignado> servicios = obtenerServiciosDe(atencion);
        byte[] pdf = ticketPdfRenderer.renderIndividual(atencion, servicios, anchoMm);
        return new TicketPdf(pdf, "ticket-" + atencion.getNumeroCorrelativo() + ".pdf", false, 1);
    }

    private TicketPdf generarGrupal(List<Atencion> grupo, int anchoMm) {
        Atencion titular = grupo.get(0);

        // Un integrante es "compartido" si no tiene servicios propios
        // (heredó los del titular al registrarse por el modal grupal).
        boolean compartidos = grupo.stream()
                .skip(1)
                .allMatch(a -> servicioAsignadoRepository.findByAtencionId(a.getId()).isEmpty());

        Map<Long, List<ServicioAsignado>> serviciosPorAtencion = obtenerServiciosPorAtencion(grupo, compartidos);

        byte[] pdf = ticketPdfRenderer.renderGrupal(grupo, serviciosPorAtencion, compartidos, anchoMm);
        return new TicketPdf(pdf, "ticket-GRUPO-" + titular.getNumeroCorrelativo() + ".pdf", true, grupo.size());
    }

    /** Igual que AtencionVoucherServiceImpl.obtenerServiciosPorAtencion. */
    private Map<Long, List<ServicioAsignado>> obtenerServiciosPorAtencion(List<Atencion> atenciones,
                                                                          boolean serviciosCompartidos) {
        Map<Long, List<ServicioAsignado>> resultado = new HashMap<>();
        if (serviciosCompartidos) {
            List<ServicioAsignado> compartidos = obtenerServiciosDe(atenciones.get(0));
            for (Atencion a : atenciones) {
                resultado.put(a.getId(), compartidos);
            }
        } else {
            for (Atencion a : atenciones) {
                resultado.put(a.getId(), servicioAsignadoRepository.findByAtencionId(a.getId()));
            }
        }
        return resultado;
    }

    /** Igual que AtencionVoucherServiceImpl.obtenerServiciosDe. */
    private List<ServicioAsignado> obtenerServiciosDe(Atencion atencion) {
        List<ServicioAsignado> propios = servicioAsignadoRepository.findByAtencionId(atencion.getId());
        if (!propios.isEmpty() || atencion.getGrupoId() == null) {
            return propios;
        }
        return servicioAsignadoRepository.findByAtencionGrupoId(atencion.getGrupoId());
    }
}