package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.enumeration.*;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.service.*;
import com.saasa.contingencias.util.AppConstants;
import com.saasa.contingencias.util.DateTimeUtil;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class EmailServiceImpl implements IEmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);
    private final JavaMailSender mailSender;
    private final AtencionRepository atencionRepository;
    private final EnvioPdfRepository envioPdfRepository;
    private final IS3StorageService s3StorageService;
    private final IAuditoriaService auditoriaService;
    // Agregar al constructor y campo:
    private final UsuarioRepository usuarioRepository;

    // ✅ Rutas de las plantillas HTML de marca SAASA (src/main/resources/template/)
    private static final String TPL_VERIFICATION_CODE = "template/verification-code.html";
    private static final String TPL_PASSWORD_RESET_CONFIRM = "template/password-reset-confirm.html";

    // ✅ NUEVAS dependencias para regenerar PDF con datos actuales
    private final ServicioAsignadoRepository servicioAsignadoRepository;
    private final IPdfGeneratorService pdfGeneratorService;

    @Value("${spring.mail.from}")
    private String fromEmail;

    public EmailServiceImpl(JavaMailSender mailSender, AtencionRepository atencionRepository,
            EnvioPdfRepository envioPdfRepository, IS3StorageService s3StorageService,
            IAuditoriaService auditoriaService,UsuarioRepository usuarioRepository,
                            ServicioAsignadoRepository servicioAsignadoRepository,
                            IPdfGeneratorService pdfGeneratorService) {
        this.mailSender = mailSender;
        this.atencionRepository = atencionRepository;
        this.envioPdfRepository = envioPdfRepository;
        this.s3StorageService = s3StorageService;
        this.auditoriaService = auditoriaService;
        this.usuarioRepository = usuarioRepository;
        this.servicioAsignadoRepository = servicioAsignadoRepository;
        this.pdfGeneratorService = pdfGeneratorService;
    }

    /** NUEVO — filtra nulos/blancos/duplicados y excluye el propio correoDestino de la lista CC. */
    private String[] limpiarCc(String correoDestino, List<String> ccDestinos) {
        if (ccDestinos == null || ccDestinos.isEmpty()) return null;
        List<String> limpio = ccDestinos.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .distinct()
                .filter(c -> correoDestino == null || !c.equalsIgnoreCase(correoDestino.trim()))
                .toList();
        return limpio.isEmpty() ? null : limpio.toArray(new String[0]);
    }

    @Async
    @Override
    public void enviarVoucher(String correoDestino,List<String> ccDestinos, String correlativo, byte[] pdfBytes, String nombrePasajero,IdiomaVoucherEnum idioma) {
        // FIX — primer envío (asignación inicial de servicios).
        enviarVoucherInterno(correoDestino, ccDestinos, correlativo, pdfBytes, nombrePasajero, false,idioma);
    }

    @Async
    @Override
    public void enviarVoucherActualizado(String correoDestino, List<String> ccDestinos, String correlativo,
                                         byte[] pdfBytes, String nombrePasajero,IdiomaVoucherEnum idioma) {
        // NUEVO — reenvío por actualización: mismo flujo de enviarVoucher
        // (el llamador ya generó/subió el PDF actualizado), pero el mensaje
        // indica que los servicios fueron actualizados.
        enviarVoucherInterno(correoDestino, ccDestinos, correlativo, pdfBytes, nombrePasajero, true,idioma);
    }

    /**
     * NUEVO — lógica compartida entre enviarVoucher (primer envío) y
     * enviarVoucherActualizado (reenvío por actualización). Solo cambia el
     * texto del mensaje según {@code esActualizacion}.
     */
    private void enviarVoucherInterno(String correoDestino, List<String> ccDestinos, String correlativo,
                                      byte[] pdfBytes, String nombrePasajero, boolean esActualizacion,IdiomaVoucherEnum idioma) {
        int intentos = 0;
        EstadoEnvioEnum estadoFinal = EstadoEnvioEnum.FALLIDO;
        int intentosFinal = 0;
        String[] cc = limpiarCc(correoDestino, ccDestinos);
        boolean ingles = idioma == IdiomaVoucherEnum.EN;
        String saludo = ingles ? "Dear " : "Estimado/a ";
        String textoEstado = ingles
                ? (esActualizacion
                ? "Please find attached your updated services voucher."
                : "Please find attached your assigned services voucher.")
                : (esActualizacion
                ? "Adjunto encontrará su comprobante de servicios actualizados."
                : "Adjunto encontrará su comprobante de servicios asignados.");

        while (intentos < AppConstants.MAX_EMAIL_RETRIES) {
            try {
                MimeMessage msg = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
                helper.setFrom(fromEmail);
                helper.setTo(correoDestino);
                if (cc != null) helper.setCc(cc);
                helper.setSubject(asuntoVoucher(correlativo, idioma));
                helper.setText("<p>" + saludo + nombrePasajero + ",</p><p>" + textoEstado + "</p>", true);
                helper.addAttachment(correlativo + ".pdf", () -> new java.io.ByteArrayInputStream(pdfBytes), "application/pdf");
                mailSender.send(msg);
                log.info("Email enviado a {} – correlativo {}", correoDestino, correlativo);
                estadoFinal = EstadoEnvioEnum.EXITOSO;
                intentosFinal = intentos + 1;
                break;
            } catch (Exception e) {
                intentos++;
                intentosFinal = intentos;
                log.warn("Intento {} fallido al enviar email a {}: {}", intentos, correoDestino, e.getMessage());
            }
        }

        if (estadoFinal == EstadoEnvioEnum.FALLIDO) {
            log.error("No se pudo enviar email a {} después de {} intentos", correoDestino, AppConstants.MAX_EMAIL_RETRIES);
        }

        //Guardar en envios_pdf
        try {
            Atencion atencion = atencionRepository
                    .findByNumeroCorrelativo(correlativo)
                    .orElse(null);

            EnvioPdf envio = EnvioPdf.builder()
                    .atencion(atencion)
                    .correoDestino(correoDestino)
                    .tipoEnvio(TipoEnvioEnum.AUTOMATICO)
                    .estadoEnvio(estadoFinal)
                    .intentos(intentosFinal)
                    .enviadoEn(estadoFinal == EstadoEnvioEnum.EXITOSO ? DateTimeUtil.ahoraEnLima() : null)
                    .build();
            envioPdfRepository.save(envio);
            log.info("✅ Registro guardado en envios_pdf – correlativo: {} | estado: {}", correlativo, estadoFinal);
        } catch (Exception ex) {
            log.error("❌ Error al guardar en envios_pdf para {}: {}", correlativo, ex.getMessage());
        }
    }

    @Async
    @Override
    public void enviarVoucherGrupal(String correoDestino,List<String> ccDestinos, String correlativoGrupo, byte[] pdfBytes,
                                    List<String> nombresPasajeros,IdiomaVoucherEnum idioma) {
        // FIX — primer envío (asignación inicial de servicios).
        enviarVoucherGrupalInterno(correoDestino, ccDestinos, correlativoGrupo, pdfBytes, nombresPasajeros, false,idioma);
    }

    @Async
    @Override
    public void reenviarVoucherGrupal(String correoDestino, List<String> ccDestinos, String correlativoGrupo,
                                      byte[] pdfBytes, List<String> nombresPasajeros,IdiomaVoucherEnum idioma) {
        // NUEVO — reenvío por actualización: mismo flujo de enviarVoucherGrupal,
        // pero el mensaje indica que los servicios fueron actualizados.
        enviarVoucherGrupalInterno(correoDestino, ccDestinos, correlativoGrupo, pdfBytes, nombresPasajeros, true,idioma);
    }

    /**
     * NUEVO — lógica compartida entre enviarVoucherGrupal (primer envío) y
     * reenviarVoucherGrupal (reenvío por actualización). Mismo comportamiento
     * de reintentos/adjunto/registro en envios_pdf; solo cambia el texto del
     * mensaje según {@code esActualizacion}.
     */
    private void enviarVoucherGrupalInterno(String correoDestino, List<String> ccDestinos, String correlativoGrupo,
                                            byte[] pdfBytes, List<String> nombresPasajeros, boolean esActualizacion,IdiomaVoucherEnum idioma) {
        int intentos = 0;
        EstadoEnvioEnum estadoFinal = EstadoEnvioEnum.FALLIDO;
        int intentosFinal = 0;
        String[] cc = limpiarCc(correoDestino, ccDestinos);
        boolean ingles = idioma == IdiomaVoucherEnum.EN;

        StringBuilder listaNombres = new StringBuilder();
        for (String nombre : nombresPasajeros) {
            listaNombres.append("<li>").append(nombre).append("</li>");
        }

        String saludo = ingles ? "Dear passenger," : "Estimado/a,";
        String textoEstado = ingles
                ? (esActualizacion
                ? "Please find attached the updated services voucher for the following passengers:"
                : "Please find attached the assigned services voucher for the following passengers:")
                : (esActualizacion
                ? "Adjunto encontrará el comprobante de servicios actualizados para los siguientes pasajeros:"
                : "Adjunto encontrará el comprobante de servicios asignados para los siguientes pasajeros:");


        while (intentos < AppConstants.MAX_EMAIL_RETRIES) {
            try {
                MimeMessage msg = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
                helper.setFrom(fromEmail);
                helper.setTo(correoDestino);
                if (cc != null) helper.setCc(cc);
                helper.setSubject(asuntoVoucher(correlativoGrupo, idioma));
                helper.setText(
                        "<p>" + saludo + "</p>"
                                + "<p>" + textoEstado + "</p>"
                                + "<ul>" + listaNombres + "</ul>",
                        true);
                helper.addAttachment(correlativoGrupo + ".pdf", () -> new java.io.ByteArrayInputStream(pdfBytes), "application/pdf");
                mailSender.send(msg);
                log.info("Email grupal enviado a {} – correlativo {} ({} pasajeros)",
                        correoDestino, correlativoGrupo, nombresPasajeros.size());
                estadoFinal = EstadoEnvioEnum.EXITOSO;
                intentosFinal = intentos + 1;
                break;
            } catch (Exception e) {
                intentos++;
                intentosFinal = intentos;
                log.warn("Intento {} fallido al enviar email grupal a {}: {}", intentos, correoDestino, e.getMessage());
            }
        }

        if (estadoFinal == EstadoEnvioEnum.FALLIDO) {
            log.error("No se pudo enviar email grupal a {} después de {} intentos", correoDestino, AppConstants.MAX_EMAIL_RETRIES);
        }

        // ✅ Guardar en envios_pdf (referenciando la primera atención del correlativo del grupo)
        try {
            String correlativoTitular = correlativoGrupo.split(" ")[0];
            Atencion atencion = atencionRepository
                    .findByNumeroCorrelativo(correlativoTitular)
                    .orElse(null);

            EnvioPdf envio = EnvioPdf.builder()
                    .atencion(atencion)
                    .correoDestino(correoDestino)
                    .tipoEnvio(TipoEnvioEnum.AUTOMATICO)
                    .estadoEnvio(estadoFinal)
                    .intentos(intentosFinal)
                    .enviadoEn(estadoFinal == EstadoEnvioEnum.EXITOSO ? DateTimeUtil.ahoraEnLima() : null)
                    .build();
            envioPdfRepository.save(envio);
            log.info("✅ Registro guardado en envios_pdf (grupal) – correlativo: {} | estado: {}", correlativoGrupo, estadoFinal);
        } catch (Exception ex) {
            log.error("❌ Error al guardar en envios_pdf (grupal) para {}: {}", correlativoGrupo, ex.getMessage());
        }
    }

    @Async
    @Override
    public void reenviarVoucher(Long atencionId, String correoDestino,List<String> ccDestinos, Long usuarioId) {
        Atencion atencion = atencionRepository.findById(atencionId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Atención no encontrada: " + atencionId));

        // ✅ FIX: Regenerar PDF con los servicios ACTUALES de BD
        //    (antes descargaba el PDF viejo de S3 que no tenía los cambios guardados)
        List<ServicioAsignado> servicios = servicioAsignadoRepository.findByAtencionId(atencionId);
        byte[] pdfBytes = pdfGeneratorService.generarVoucher(atencion, servicios);

        // ✅ Sobreescribir PDF en S3 con la versión actualizada
        String fileName = atencion.getNumeroCorrelativo() + ".pdf";
        String nuevaPdfUrl = s3StorageService.subirPdf(pdfBytes, fileName);
        atencion.setPdfUrl(nuevaPdfUrl);
        atencionRepository.save(atencion);
        log.info("✅ PDF regenerado y subido a S3: {}", nuevaPdfUrl);

        Usuario usuario = usuarioId != null
                ? usuarioRepository.findById(usuarioId).orElse(null)
                : null;

        String nombreCompleto = atencion.getNombre() + " " + atencion.getApellido();
        EstadoEnvioEnum estadoFinal = EstadoEnvioEnum.FALLIDO;
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(fromEmail);

            helper.setTo(correoDestino);
            String[] cc = limpiarCc(correoDestino, ccDestinos);
            if (cc != null) helper.setCc(cc);
            IdiomaVoucherEnum idioma = atencion.getIdiomaVoucher() != null
                    ? atencion.getIdiomaVoucher() : IdiomaVoucherEnum.ES;
            boolean ingles = idioma == IdiomaVoucherEnum.EN;
            helper.setSubject(asuntoVoucher(atencion.getNumeroCorrelativo(), idioma));
            String saludo = ingles ? "Dear " : "Estimado/a ";
            String textoEstado = ingles
                    ? "Please find attached your updated services voucher."
                    : "Adjunto encontrará su comprobante de servicios actualizados.";
            helper.setText("<p>" + saludo + nombreCompleto + ",</p>" +
                    "<p>" + textoEstado + "</p>", true);

            helper.addAttachment(atencion.getNumeroCorrelativo() + ".pdf",
                    () -> new java.io.ByteArrayInputStream(pdfBytes), "application/pdf");
            mailSender.send(msg);
            log.info("✅ Reenvío exitoso a {} – correlativo {}", correoDestino, atencion.getNumeroCorrelativo());
            estadoFinal = EstadoEnvioEnum.EXITOSO;
        } catch (Exception e) {
            log.error("❌ Error en reenvío a {}: {}", correoDestino, e.getMessage());
        }

        EnvioPdf envio = EnvioPdf.builder()
                .atencion(atencion)
                .correoDestino(correoDestino)
                .tipoEnvio(TipoEnvioEnum.REENVIO)
                .estadoEnvio(estadoFinal)
                .intentos(1)
                .enviadoEn(estadoFinal == EstadoEnvioEnum.EXITOSO ? DateTimeUtil.ahoraEnLima() : null)
                .enviadoPor(usuario)
                .build();
        envioPdfRepository.save(envio);

        auditoriaService.registrar(usuarioId, "REENVIAR_PDF", "ATENCIONES", null,
                java.util.Map.of("atencionId", atencionId, "correo", correoDestino));
    }

    private String cargarPlantilla(String rutaClasspath) {
        try (InputStream is = new ClassPathResource(rutaClasspath).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("No se pudo cargar la plantilla de correo {}: {}", rutaClasspath, e.getMessage());
            throw new IllegalStateException("Plantilla de correo no disponible: " + rutaClasspath, e);
        }
    }

    private String reemplazarPlaceholders(String plantilla, java.util.Map<String, String> valores) {
        String resultado = plantilla;
        for (var entry : valores.entrySet()) {
            resultado = resultado.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return resultado;
    }

    private String asuntoVoucher(String correlativo, IdiomaVoucherEnum idioma) {
        return idioma == IdiomaVoucherEnum.EN
                ? "Service Voucher – " + correlativo
                : "Voucher de servicios – " + correlativo;
    }

    @Async
    @Override
    public void enviarCodigoVerificacion(String correo, String nombre, String codigo, int minutos) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(correo);
            helper.setSubject("Código de verificación – SAASA");

            String html = reemplazarPlaceholders(cargarPlantilla(TPL_VERIFICATION_CODE), java.util.Map.of(
                    "nombre", nombre,
                    "codigo", codigo,
                    "minutos", String.valueOf(minutos)
            ));
            helper.setText(html, true);

            mailSender.send(msg);
        } catch (Exception e) {
            log.error("Error enviando código de verificación a {}", correo, e);
        }
    }

    @Async
    @Override
    public void enviarConfirmacionReset(String correo, String nombre, String nuevaPassword) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(correo);
            helper.setSubject("Contraseña actualizada – SAASA");

            String fechaHora = DateTimeUtil.formatearFechaHora(DateTimeUtil.ahoraEnLima());
            String html = reemplazarPlaceholders(cargarPlantilla(TPL_PASSWORD_RESET_CONFIRM), java.util.Map.of(
                    "nombre", nombre,
                    "correo", correo,
                    "nuevaPassword", nuevaPassword,
                    "fechaHora", fechaHora
            ));
            helper.setText(html, true);

            mailSender.send(msg);
        } catch (Exception e) {
            log.error("Error enviando confirmación de reset a {}", correo, e);
        }
    }

}
