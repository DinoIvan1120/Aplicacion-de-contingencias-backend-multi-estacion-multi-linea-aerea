package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.exception.PdfGenerationException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.dto.request.EnvioEmailRequest;
import com.saasa.contingencias.domain.dto.request.GenerarVoucherRequest;
import com.saasa.contingencias.domain.dto.request.VoucherGrupalRequest;
import com.saasa.contingencias.domain.dto.response.VoucherGrupalResponse;
import com.saasa.contingencias.domain.dto.response.VoucherResponse;
import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.model.ServicioAsignado;
import com.saasa.contingencias.domain.repository.AtencionRepository;
import com.saasa.contingencias.domain.repository.ServicioAsignadoRepository;
import com.saasa.contingencias.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AtencionVoucherServiceImpl implements IAtencionVoucherService {

    private static final Logger log = LoggerFactory.getLogger(AtencionVoucherServiceImpl.class);

    private final AtencionRepository atencionRepository;
    private final ServicioAsignadoRepository servicioAsignadoRepository;
    private final IPdfGeneratorService pdfGeneratorService;
    private final IS3StorageService s3StorageService;
    private final IEmailService emailService;
    private final IWhatsAppService whatsAppService;
    private final IDisponibilidadService disponibilidadService;
    private final IAuditoriaService auditoriaService;

    public AtencionVoucherServiceImpl(
            AtencionRepository atencionRepository,
            ServicioAsignadoRepository servicioAsignadoRepository,
            IPdfGeneratorService pdfGeneratorService,
            IS3StorageService s3StorageService,
            IEmailService emailService,
            IWhatsAppService whatsAppService,
            IDisponibilidadService disponibilidadService,
            IAuditoriaService auditoriaService) {
        this.atencionRepository = atencionRepository;
        this.servicioAsignadoRepository = servicioAsignadoRepository;
        this.pdfGeneratorService = pdfGeneratorService;
        this.s3StorageService = s3StorageService;
        this.emailService = emailService;
        this.whatsAppService = whatsAppService;
        this.disponibilidadService = disponibilidadService;
        this.auditoriaService = auditoriaService;
    }

    @Override
    @Transactional
    public VoucherResponse generarVoucherPdf(Long atencionId) {
        Atencion atencion = getOrThrow(atencionId);
        //List<ServicioAsignado> servicios = servicioAsignadoRepository.findByAtencionId(atencionId);
        List<ServicioAsignado> servicios = obtenerServiciosDe(atencion);
        byte[] pdfBytes;
        try {
            pdfBytes = pdfGeneratorService.generarVoucher(atencion, servicios);
        } catch (Exception e) {
            throw new PdfGenerationException("Error al generar voucher para " + atencionId, e);
        }
        String pdfUrl = s3StorageService.subirPdf(pdfBytes, atencion.getNumeroCorrelativo() + ".pdf");
        atencion.setPdfUrl(pdfUrl);
        atencionRepository.save(atencion);
        notificarDisponibilidad(atencion);
        return VoucherResponse.soloGenerado(atencion.getNumeroCorrelativo(), pdfUrl);
    }

    @Override
    @Transactional
    public VoucherResponse generarYEnviarVoucher(Long atencionId, GenerarVoucherRequest request, Long usuarioId) {
        Atencion atencion = getOrThrow(atencionId);

        // NUEVO — Idioma elegido por el agente en el modal "Confirmar y Enviar Voucher".
        // Por defecto se genera en Español (ES) si no se especifica.
        IdiomaVoucherEnum idioma = request.idiomaVoucher() != null && !request.idiomaVoucher().isBlank()
                ? IdiomaVoucherEnum.valueOf(request.idiomaVoucher().trim().toUpperCase())
                : IdiomaVoucherEnum.ES;
        atencion.setIdiomaVoucher(idioma);

        //List<ServicioAsignado> servicios = servicioAsignadoRepository.findByAtencionId(atencionId);
        List<ServicioAsignado> servicios = obtenerServiciosDe(atencion);
        byte[] pdfBytes;
        try {
            pdfBytes = pdfGeneratorService.generarVoucher(atencion, servicios);
        } catch (Exception e) {
            throw new PdfGenerationException("Error al generar voucher para " + atencionId, e);
        }
        String pdfUrl = s3StorageService.subirPdf(pdfBytes, atencion.getNumeroCorrelativo() + ".pdf");
        atencion.setPdfUrl(pdfUrl);
        atencionRepository.save(atencion);

        // NUEVO: firma digital de conformidad capturada en el modal del agente
        if (request.firmaPasajero() != null && !request.firmaPasajero().isBlank()) {
            atencion.setFirmaPasajero(request.firmaPasajero());
            atencion.setFirmaConforme(true);
            atencion.setFirmaFecha(com.saasa.contingencias.util.DateTimeUtil.ahoraEnLima());
            // NUEVO — traza quién firmó: pasajero (default) o agente/líder/administrador autorizando un lote
            boolean esLote = "AGENTE_LOTE".equals(request.origenFirma());
            // NUEVO — traza quién firmó: pasajero (default) o agente autorizando un lote
            atencion.setOrigenFirma("AGENTE_LOTE".equals(request.origenFirma())
                    ? com.saasa.contingencias.domain.enumeration.OrigenFirmaEnum.AGENTE_LOTE
                    : com.saasa.contingencias.domain.enumeration.OrigenFirmaEnum.PASAJERO);
            // NUEVO — rol real de quien autorizó (solo aplica si es carga masiva)
            atencion.setOrigenFirmaRol(esLote ? request.origenFirmaRol() : null);
            atencionRepository.save(atencion);
        }

        String correoDestino = request.correoDestino() != null ? request.correoDestino() : atencion.getCorreo();
        String nombreCompleto = atencion.getNombre() + " " + atencion.getApellido();
        emailService.enviarVoucher(correoDestino,request.ccDestinos(), atencion.getNumeroCorrelativo(), pdfBytes, nombreCompleto,idioma);

        if (atencion.getTelefono() != null && !atencion.getTelefono().isBlank()) {
            try {
                String urlWhatsApp = s3StorageService.generarUrlConExpiracion(pdfUrl, 7);
                whatsAppService.enviarVoucherWhatsApp(
                        atencion.getTelefono(), atencion.getNumeroCorrelativo(), nombreCompleto, urlWhatsApp,idioma);
            } catch (Exception e) {
                log.warn("No se pudo enviar WhatsApp para {}: {}", atencion.getNumeroCorrelativo(), e.getMessage());
            }
        }
        notificarDisponibilidad(atencion);
        return VoucherResponse.generadoYEnviado(atencion.getNumeroCorrelativo(), pdfUrl, correoDestino);
    }

    @Override
    @Transactional
    public String generarYEnviarVoucherLegacy(Long atencionId, Long usuarioId) {
        Atencion atencion = getOrThrow(atencionId);
        //List<ServicioAsignado> servicios = servicioAsignadoRepository.findByAtencionId(atencionId);
        List<ServicioAsignado> servicios = obtenerServiciosDe(atencion);
        byte[] pdf;
        try {
            pdf = pdfGeneratorService.generarVoucher(atencion, servicios);
        } catch (Exception e) {
            throw new PdfGenerationException("Error al generar voucher legacy para " + atencionId, e);
        }
        String url = s3StorageService.subirPdf(pdf, atencion.getNumeroCorrelativo());
        atencion.setPdfUrl(url);
        atencionRepository.save(atencion);
        emailService.enviarVoucher(atencion.getCorreo(),null, atencion.getNumeroCorrelativo(),
                pdf, atencion.getNombre() + " " + atencion.getApellido(),atencion.getIdiomaVoucher()!=null ?atencion.getIdiomaVoucher(): IdiomaVoucherEnum.ES);
        notificarDisponibilidad(atencion);
        return url;
    }


    // ═══════════════════════════════════════════════════════════════════════
    // NUEVO — Voucher grupal (un PDF para varios pasajeros con mismo PNR/correo)
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public VoucherGrupalResponse generarVoucherGrupalPdf(VoucherGrupalRequest request) {
        List<Atencion> atenciones = obtenerAtencionesGrupo(request.atencionIds());
        boolean compartidos = Boolean.TRUE.equals(request.serviciosCompartidos());
        Map<Long, List<ServicioAsignado>> serviciosPorAtencion = obtenerServiciosPorAtencion(atenciones, compartidos);

        byte[] pdfBytes;
        try {
            pdfBytes = pdfGeneratorService.generarVoucherGrupal(atenciones, serviciosPorAtencion, compartidos);
        } catch (Exception e) {
            throw new PdfGenerationException("Error al generar voucher grupal", e);
        }

        Atencion titular = atenciones.get(0);
        String pdfUrl = s3StorageService.subirPdf(pdfBytes, "GRUPO-" + titular.getNumeroCorrelativo() + ".pdf");
        for (Atencion a : atenciones) {
            a.setPdfUrl(pdfUrl);
        }
        propagarMontoYCodigoDelTitular(atenciones, titular, compartidos);
        atencionRepository.saveAll(atenciones);
        notificarDisponibilidad(titular);

        List<String> correlativos = atenciones.stream().map(Atencion::getNumeroCorrelativo).collect(Collectors.toList());
        return VoucherGrupalResponse.soloGenerado(correlativos, pdfUrl);
    }

    @Override
    @Transactional
    public VoucherGrupalResponse generarYEnviarVoucherGrupal(VoucherGrupalRequest request, Long usuarioId) {
        List<Atencion> atenciones = obtenerAtencionesGrupo(request.atencionIds());

        // NUEVO — Idioma elegido por el agente en el modal "Confirmar y Enviar Voucher".
        // Se aplica a TODOS los integrantes del grupo, ya que el PDF grupal es uno solo.
        // Por defecto se genera en Español (ES) si no se especifica.
        IdiomaVoucherEnum idioma = request.idiomaVoucher() != null && !request.idiomaVoucher().isBlank()
                ? IdiomaVoucherEnum.valueOf(request.idiomaVoucher().trim().toUpperCase())
                : IdiomaVoucherEnum.ES;
        for (Atencion a : atenciones) {
            a.setIdiomaVoucher(idioma);
        }

        boolean compartidos = Boolean.TRUE.equals(request.serviciosCompartidos());
        Map<Long, List<ServicioAsignado>> serviciosPorAtencion = obtenerServiciosPorAtencion(atenciones, compartidos);

        byte[] pdfBytes;
        try {
            pdfBytes = pdfGeneratorService.generarVoucherGrupal(atenciones, serviciosPorAtencion, compartidos);
        } catch (Exception e) {
            throw new PdfGenerationException("Error al generar voucher grupal", e);
        }

        Atencion titular = atenciones.get(0);
        String pdfUrl = s3StorageService.subirPdf(pdfBytes, "GRUPO-" + titular.getNumeroCorrelativo() + ".pdf");
        for (Atencion a : atenciones) {
            a.setPdfUrl(pdfUrl);
        }
        propagarMontoYCodigoDelTitular(atenciones, titular, compartidos);
        atencionRepository.saveAll(atenciones);

        String correoDestino = (request.correoDestino() != null && !request.correoDestino().isBlank())
                ? request.correoDestino() : titular.getCorreo();
        List<String> nombres = atenciones.stream()
                .map(a -> a.getNombre() + " " + a.getApellido())
                .collect(Collectors.toList());
        String correlativoGrupo = atenciones.size() > 1
                ? titular.getNumeroCorrelativo() + " (+" + (atenciones.size() - 1) + ")"
                : titular.getNumeroCorrelativo();

        // NUEVO: firma digital de conformidad — se aplica al titular del grupo
        if (request.firmaPasajero() != null && !request.firmaPasajero().isBlank()) {
            boolean esLote = "AGENTE_LOTE".equals(request.origenFirma());
            com.saasa.contingencias.domain.enumeration.OrigenFirmaEnum origen =
                    "AGENTE_LOTE".equals(request.origenFirma())
                            ? com.saasa.contingencias.domain.enumeration.OrigenFirmaEnum.AGENTE_LOTE
                            : com.saasa.contingencias.domain.enumeration.OrigenFirmaEnum.PASAJERO;
            // NUEVO — rol real de quien autorizó (solo aplica si es carga masiva)
            String origenRol = esLote ? request.origenFirmaRol() : null;
            for (Atencion a : atenciones) {
                a.setFirmaPasajero(request.firmaPasajero());
                a.setFirmaConforme(true);
                a.setFirmaFecha(com.saasa.contingencias.util.DateTimeUtil.ahoraEnLima());
                a.setOrigenFirma(origen);
                a.setOrigenFirmaRol(origenRol);
            }
            atencionRepository.saveAll(atenciones);
        }
        emailService.enviarVoucherGrupal(correoDestino,request.ccDestinos(), correlativoGrupo, pdfBytes, nombres,idioma);

        // FIX — evitar envíos duplicados por WhatsApp cuando varios
        // integrantes del grupo comparten el mismo número de teléfono
        // (p. ej. en carga masiva por Excel, donde los pasajeros sin
        // celular propio heredan el celular del titular). Antes se
        // enviaba el mismo PDF grupal una vez POR CADA integrante que
        // tuviera ese teléfono asociado; ahora se envía UNA sola vez por
        // cada número de teléfono distinto, de modo que el titular (u
        // otro integrante con un número realmente distinto) reciba un
        // único mensaje con el PDF grupal completo.
        String urlWhatsApp = null;
        java.util.Set<String> telefonosNotificados = new java.util.HashSet<>();
        for (Atencion a : atenciones) {
            String telefono = a.getTelefono();
            if (telefono != null && !telefono.isBlank() && telefonosNotificados.add(telefono.trim())) {
                try {
                    if (urlWhatsApp == null) {
                        urlWhatsApp = s3StorageService.generarUrlConExpiracion(pdfUrl, 7);
                    }
                    whatsAppService.enviarVoucherWhatsApp(
                            telefono, a.getNumeroCorrelativo(), a.getNombre() + " " + a.getApellido(), urlWhatsApp,idioma);
                } catch (Exception e) {
                    log.warn("No se pudo enviar WhatsApp para {}: {}", a.getNumeroCorrelativo(), e.getMessage());
                }
            }
        }

        notificarDisponibilidad(titular);
        List<String> correlativos = atenciones.stream().map(Atencion::getNumeroCorrelativo).collect(Collectors.toList());
        return VoucherGrupalResponse.generadoYEnviado(correlativos, pdfUrl, correoDestino);
    }

    private List<Atencion> obtenerAtencionesGrupo(List<Long> atencionIds) {
        List<Atencion> atenciones = atencionIds.stream().map(this::getOrThrow).collect(Collectors.toList());
        if (atenciones.isEmpty()) {
            throw new BadRequestException("Debe indicar al menos una atención para el voucher grupal");
        }
        String pnr = atenciones.get(0).getPnr();
        Long vueloId = atenciones.get(0).getVuelo().getId();
        for (Atencion a : atenciones) {
            if (!pnr.equals(a.getPnr()) || !vueloId.equals(a.getVuelo().getId())) {
                throw new BadRequestException(
                        "Las atenciones del voucher grupal deben compartir el mismo PNR y el mismo vuelo");
            }
        }
        return atenciones;
    }

    private Map<Long, List<ServicioAsignado>> obtenerServiciosPorAtencion(List<Atencion> atenciones, boolean serviciosCompartidos) {
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

    private void propagarMontoYCodigoDelTitular(List<Atencion> atenciones, Atencion titular, boolean compartidos) {
        if (!compartidos) return;
        // NOTA: NO se propaga codigoAutorizacion al resto del grupo.
        // Cada Atencion ya trae su propio codigoAutorizacion único
        // (generado en AtencionServiceImpl al asignar servicios), y la
        // columna tiene una restricción UNIQUE en BD. Copiar el mismo
        // valor del titular a 2+ integrantes viola esa restricción y
        // provoca un 500 (DataIntegrityViolationException) al hacer
        // saveAll(). Solo el monto total tiene sentido compartir aquí.
        for (Atencion a : atenciones) {
            if (!a.getId().equals(titular.getId())) {
                a.setMontoTotal(titular.getMontoTotal());
            }
        }
    }

    private List<ServicioAsignado> obtenerServiciosDe(Atencion atencion) {
        List<ServicioAsignado> propios = servicioAsignadoRepository.findByAtencionId(atencion.getId());
        if (!propios.isEmpty() || atencion.getGrupoId() == null) {
            return propios;
        }
        return servicioAsignadoRepository.findByAtencionGrupoId(atencion.getGrupoId());
    }


    @Override
    @Transactional
    public void reenviarPdf(Long atencionId, EnvioEmailRequest request, Long usuarioId) {
        Atencion atencion = getOrThrow(atencionId);

        if (request.telefono() != null && !request.telefono().isBlank()
                && !request.telefono().equals(atencion.getTelefono())) {
            atencion.setTelefono(request.telefono());
            atencionRepository.save(atencion);
        }

        // ── FIX: si el pasajero pertenece a un voucher GRUPAL (mismo PNR/correo
        // compartido con otros), reenviamos el PDF CONJUNTO regenerado con los
        // datos actuales de TODO el grupo — no un voucher individual, que
        // rompía el comprobante compartido al editar solo un integrante. ──
        List<Atencion> grupo = (atencion.getGrupoId() != null)
                ? atencionRepository.findByGrupoIdOrderByIdAsc(atencion.getGrupoId())
                : List.of();

        if (grupo.size() > 1) {
            reenviarVoucherGrupal(grupo, atencionId, request, usuarioId);
        } else {
            emailService.reenviarVoucher(atencionId, request.correoDestino(),request.ccDestinos(), usuarioId);
            if (request.telefono() != null && !request.telefono().isBlank()) {
                whatsAppService.reenviarVoucherWhatsApp(atencionId, request.telefono(), usuarioId,null);
            }
        }
    }

    /**
     * Regenera el PDF grupal a partir de los datos ACTUALES de todos los
     * integrantes del grupo (así refleja cualquier edición reciente de
     * cualquiera de ellos) y lo reenvía como UN solo correo conjunto,
     * igual que en el envío original.
     */
    private void reenviarVoucherGrupal(List<Atencion> atenciones, Long atencionIdOrigen,
                                       EnvioEmailRequest request, Long usuarioId) {
        Atencion titular = atenciones.get(0);

        // Un integrante se considera "compartido" si no tiene servicios propios
        // asignados (heredó los del titular al registrarse por el modal grupal).
        boolean compartidos = atenciones.stream()
                .skip(1)
                .allMatch(a -> servicioAsignadoRepository.findByAtencionId(a.getId()).isEmpty());

        Map<Long, List<ServicioAsignado>> serviciosPorAtencion = obtenerServiciosPorAtencion(atenciones, compartidos);

        byte[] pdfBytes;
        try {
            pdfBytes = pdfGeneratorService.generarVoucherGrupal(atenciones, serviciosPorAtencion, compartidos);
        } catch (Exception e) {
            throw new PdfGenerationException("Error al regenerar voucher grupal para reenvío", e);
        }

        String pdfUrl = s3StorageService.subirPdf(pdfBytes, "GRUPO-" + titular.getNumeroCorrelativo() + ".pdf");
        for (Atencion a : atenciones) {
            a.setPdfUrl(pdfUrl);
        }
        atencionRepository.saveAll(atenciones);

        String correoDestino = (request.correoDestino() != null && !request.correoDestino().isBlank())
                ? request.correoDestino() : titular.getCorreo();
        List<String> nombres = atenciones.stream()
                .map(a -> a.getNombre() + " " + a.getApellido())
                .collect(Collectors.toList());
        String correlativoGrupo = titular.getNumeroCorrelativo() + " (+" + (atenciones.size() - 1) + ")";
        // FIX — este es un reenvío por actualización del PDF grupal, no la
        // asignación inicial; usar reenviarVoucherGrupal() para que el
        // mensaje indique "servicios actualizados" en vez de repetir el
        // texto de "servicios asignados" del primer envío.
        IdiomaVoucherEnum idiomaGrupo = titular.getIdiomaVoucher() != null
                ? titular.getIdiomaVoucher() : IdiomaVoucherEnum.ES;
        emailService.reenviarVoucherGrupal(correoDestino,request.ccDestinos(), correlativoGrupo, pdfBytes, nombres,idiomaGrupo);

        if (request.telefono() != null && !request.telefono().isBlank()) {
            whatsAppService.reenviarVoucherWhatsApp(atencionIdOrigen, request.telefono(), usuarioId,idiomaGrupo);
        }

        auditoriaService.registrar(usuarioId, "REENVIAR_PDF_GRUPAL", "ATENCIONES", null,
                Map.of("grupoId", titular.getGrupoId(), "correo", correoDestino,
                        "atencionIds", atenciones.stream().map(Atencion::getId).collect(Collectors.toList())));
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] descargarPdf(Long atencionId) {
        return s3StorageService.descargarPdf(getOrThrow(atencionId).getPdfUrl());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> urlFirmadaVoucher(Long atencionId) {
        String urlFirmada = s3StorageService.generarUrlFirmada(getOrThrow(atencionId).getPdfUrl());
        return Map.of(
                "success", "true",
                "downloadUrl", urlFirmada,
                "expiresIn", "15 minutos"
        );
    }

    private Atencion getOrThrow(Long id) {
        return atencionRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Atención no encontrada: " + id));
    }

    private void notificarDisponibilidad(Atencion atencion) {
        if (atencion.getRegistroVueloDiario() != null) {
            try {
                disponibilidadService.notificarCambioDisponibilidad(
                        atencion.getRegistroVueloDiario().getId());
            } catch (Exception e) {
                log.warn("No se pudo notificar disponibilidad: {}", e.getMessage());
            }
        }
    }
}
