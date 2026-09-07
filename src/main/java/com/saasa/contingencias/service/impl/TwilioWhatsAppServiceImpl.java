package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.enumeration.EstadoEnvioEnum;
import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;
import com.saasa.contingencias.domain.enumeration.TipoEnvioEnum;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.model.EnvioPdf;
import com.saasa.contingencias.domain.model.Usuario;
import com.saasa.contingencias.domain.repository.AtencionRepository;
import com.saasa.contingencias.domain.repository.EnvioPdfRepository;
import com.saasa.contingencias.domain.repository.UsuarioRepository;
import com.saasa.contingencias.service.IAuditoriaService;
//import com.saasa.contingencias.service.IS3StorageService;
import com.saasa.contingencias.service.IWhatsAppService;
import com.saasa.contingencias.util.DateTimeUtil;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Implementación de {@link} usando Twilio API.
 *
 * <p><b>Modo QAS (Sandbox):</b> El pasajero debe haber enviado previamente
 * "join &lt;sandbox-keyword&gt;" al número +1 415 523 8886 desde su WhatsApp.</p>
 *
 * <p><b>Modo PRD:</b> Requiere número aprobado por Meta (WhatsApp Business API)
 * y actualizar TWILIO_WHATSAPP_FROM en el .env de producción.</p>
 */
@Service
public class TwilioWhatsAppServiceImpl implements IWhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(TwilioWhatsAppServiceImpl.class);
    private static final int MAX_RETRIES = 2;

    private final AtencionRepository atencionRepository;
    private final EnvioPdfRepository envioPdfRepository;
    private final UsuarioRepository usuarioRepository;
    private final IAuditoriaService auditoriaService;

    // ✅ Para generar URL firmada real al reenviar por WhatsApp
    //private final IS3StorageService s3StorageService;

    @Value("${twilio.whatsapp.from}")
    private String fromNumber; // "whatsapp:+14155238886" (Sandbox) o número aprobado en PRD

    // ✅ Dominio público propio usado para generar el enlace corto del voucher
    //(evita exponer la URL firmada real de S3 con bucket/firma en el mensaje).
    //La URL firmada real se genera al vuelo cuando el usuario hace clic,
    //en VoucherRedirectController (GET /v/{correlativo}).
    @Value("${app.public-base-url}")
    private String publicBaseUrl;

    public TwilioWhatsAppServiceImpl(AtencionRepository atencionRepository,
                                     EnvioPdfRepository envioPdfRepository,
                                     UsuarioRepository usuarioRepository,
                                     //IAuditoriaService auditoriaService
                                     //IS3StorageService s3StorageService
                                     IAuditoriaService auditoriaService) {
        this.atencionRepository = atencionRepository;
        this.envioPdfRepository = envioPdfRepository;
        this.usuarioRepository = usuarioRepository;
        this.auditoriaService = auditoriaService;
        //this.s3StorageService = s3StorageService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Envío automático (al generar voucher)
    // ─────────────────────────────────────────────────────────────────────────

    @Async
    @Override
    public void enviarVoucherWhatsApp(String telefono, String correlativo,
                                      String nombrePasajero, String pdfUrl,IdiomaVoucherEnum idioma) {

        if (telefono == null || telefono.isBlank()) {
            log.warn("⚠️  WhatsApp omitido para {} – teléfono no registrado.", correlativo);
            return;
        }

        String toNumber = "whatsapp:" + telefono;
        //String cuerpo   = buildMensajeVoucher(nombrePasajero, correlativo, pdfUrl);
        // ✅ Se envía el enlace corto propio (/v/{correlativo}), no la URL firmada de S3
        // FIX — se pasa idioma: antes el mensaje siempre salía en español
        // aunque el PDF/correo se hubiera generado en inglés.
        String cuerpo = buildMensajeVoucher(nombrePasajero, correlativo, buildEnlaceCorto(correlativo), false,idioma);

        int intentos = 0;
        EstadoEnvioEnum estadoFinal = EstadoEnvioEnum.FALLIDO;

        while (intentos < MAX_RETRIES) {
            try {
                Message.creator(
                        new PhoneNumber(toNumber),
                        new PhoneNumber(fromNumber),
                        cuerpo
                ).create();

                log.info("✅ WhatsApp enviado a {} – correlativo {}", telefono, correlativo);
                estadoFinal = EstadoEnvioEnum.EXITOSO;
                intentos++;
                break;

            } catch (Exception e) {
                intentos++;
                log.warn("⚠️  Intento {} fallido al enviar WhatsApp a {}: {}", intentos, telefono, e.getMessage());
            }
        }

        if (estadoFinal == EstadoEnvioEnum.FALLIDO) {
            log.error("❌ No se pudo enviar WhatsApp a {} después de {} intentos", telefono, MAX_RETRIES);
        }

        persistirEnvio(correlativo, telefono, TipoEnvioEnum.WHATSAPP, estadoFinal, intentos, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reenvío manual (disparado por agente/líder)
    // ─────────────────────────────────────────────────────────────────────────

    @Async
    @Override
    public void reenviarVoucherWhatsApp(Long atencionId, String telefono, Long usuarioId,IdiomaVoucherEnum idioma) {

        Atencion atencion = atencionRepository.findById(atencionId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Atención no encontrada: " + atencionId));

        Usuario usuario = usuarioId != null
                ? usuarioRepository.findById(usuarioId).orElse(null)
                : null;

        String toNumber = "whatsapp:" + telefono;
        String nombrePasajero = atencion.getNombre() + " " + atencion.getApellido();
        // ✅FIX: Generar URL firmada real del PDF actualizado en S3
        //(antes se pasaba null → el mensaje llegaba con la palabra "null")
        //String pdfUrlParaWhatsApp;
        //try {
            //pdfUrlParaWhatsApp = s3StorageService.generarUrlConExpiracion(atencion.getPdfUrl(), 7);
        //} catch (Exception e) {
            //log.warn("⚠️ No se pudo generar URL firmada para WhatsApp, usando key S3 directa: {}", e.getMessage());
            //pdfUrlParaWhatsApp = atencion.getPdfUrl();
        //}

        // FIX — se usa el idioma pasado explícitamente por el llamador (el
        // elegido para ESTE reenvío). Solo se hace fallback a BD/ES cuando
        // el llamador no lo especifica, para no depender de una relectura
        // de atencion.getIdiomaVoucher() que puede aún no estar comprometida
        // (commit) si el llamador es transaccional y disparó este método
        // async antes de terminar su transacción.
        IdiomaVoucherEnum idiomaEfectivo = idioma != null
                ? idioma
                : (atencion.getIdiomaVoucher() != null ? atencion.getIdiomaVoucher() : IdiomaVoucherEnum.ES);
        String cuerpo = buildMensajeVoucher(nombrePasajero, atencion.getNumeroCorrelativo(),
                buildEnlaceCorto(atencion.getNumeroCorrelativo()), true,idiomaEfectivo);

        EstadoEnvioEnum estadoFinal = EstadoEnvioEnum.FALLIDO;
        try {
            Message.creator(
                    new PhoneNumber(toNumber),
                    new PhoneNumber(fromNumber),
                    cuerpo
            ).create();

            log.info("✅ Reenvío WhatsApp exitoso a {} – correlativo {}", telefono, atencion.getNumeroCorrelativo());
            estadoFinal = EstadoEnvioEnum.EXITOSO;

        } catch (Exception e) {
            log.error("❌ Error en reenvío WhatsApp a {}: {}", telefono, e.getMessage());
        }

        // Registrar en envios_pdf con enviadoPor
        EnvioPdf envio = EnvioPdf.builder()
                .atencion(atencion)
                .correoDestino(telefono)           // reutilizamos correoDestino para el teléfono
                .tipoEnvio(TipoEnvioEnum.WHATSAPP)
                .estadoEnvio(estadoFinal)
                .intentos(1)
                .enviadoEn(estadoFinal == EstadoEnvioEnum.EXITOSO ? DateTimeUtil.ahoraEnLima() : null)
                .enviadoPor(usuario)
                .build();
        envioPdfRepository.save(envio);

        auditoriaService.registrar(usuarioId, "REENVIAR_WHATSAPP", "ATENCIONES", null,
                Map.of("atencionId", atencionId, "telefono", telefono));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers privados
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Construye el enlace corto propio del voucher, ej:
     * {@code https://vouchers.saasa.pe/v/SGC-000000236}
     *
     * <p>Se resuelve en {@code VoucherRedirectController}, que genera la URL
     * firmada real de S3 en el momento del clic y redirige (302) a ella.</p>
     */
    private String buildEnlaceCorto(String correlativo) {
        String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        return base + "/v/" + correlativo;
    }

    /**
     * Construye el cuerpo del mensaje WhatsApp.
     *
     * <p>Nota sobre el PDF: el Sandbox de Twilio no permite adjuntos binarios;
     * se envía el link de S3 directamente. En PRD con número aprobado se puede
     * usar Media URL (parámetro mediaUrl en el Message.creator).</p>
     *
     * <p>FIX — {@code esActualizacion} diferencia el mensaje entre la
     * asignación inicial de servicios (primer envío) y un reenvío disparado
     * porque el PDF fue actualizado. Antes el texto era siempre el mismo
     * ("asignados correctamente"), incluso en reenvíos por actualización.</p>
     */
    private String buildMensajeVoucher(String nombrePasajero, String correlativo, String pdfUrl,
                                       boolean esActualizacion,IdiomaVoucherEnum idioma) {
        boolean ingles = idioma == IdiomaVoucherEnum.EN;

        String encabezado = ingles
                ? "✈️ *SAASA – Contingency Services*"
                : "✈️ *SAASA – Servicios de Contingencia*";
        String saludo = (ingles ? "Dear " : "Estimado/a ") + nombrePasajero + ",";
        String estado = ingles
                ? (esActualizacion
                ? "Your assigned services have been updated successfully."
                : "Your assigned services have been confirmed successfully.")
                : (esActualizacion
                ? "Sus servicios de atención han sido actualizados correctamente."
                : "Sus servicios de atención han sido asignados correctamente.");
        String etiquetaCodigo = ingles ? "📋 *Service code:*" : "📋 *Código de atención:*";
        String etiquetaDescarga = ingles ? "📄 Download your voucher here:" : "📄 Descargue su voucher aquí:";
        String pie = ingles
                ? "For inquiries, please contact SAASA staff at the airport."
                : "Para consultas comuníquese con el personal de SAASA en el aeropuerto.";

        return """
        %s

        %s

        %s

        %s %s

        %s
        %s

        %s
        """.formatted(encabezado, saludo, estado, etiquetaCodigo, correlativo, etiquetaDescarga, pdfUrl, pie);
    }

    /**
     * Persiste el resultado del envío automático en la tabla envios_pdf.
     */
    private void persistirEnvio(String correlativo, String telefono,
                                TipoEnvioEnum tipo, EstadoEnvioEnum estado,
                                int intentos, Usuario enviadoPor) {
        try {
            Atencion atencion = atencionRepository
                    .findByNumeroCorrelativo(correlativo)
                    .orElse(null);

            EnvioPdf envio = EnvioPdf.builder()
                    .atencion(atencion)
                    .correoDestino(telefono)       // almacenamos el teléfono en este campo
                    .tipoEnvio(tipo)
                    .estadoEnvio(estado)
                    .intentos(intentos)
                    .enviadoEn(estado == EstadoEnvioEnum.EXITOSO ? DateTimeUtil.ahoraEnLima() : null)
                    .enviadoPor(enviadoPor)
                    .build();

            envioPdfRepository.save(envio);
            log.info("✅ Registro WhatsApp guardado en envios_pdf – correlativo: {} | estado: {}",
                    correlativo, estado);

        } catch (Exception ex) {
            log.error("❌ Error al guardar en envios_pdf (WhatsApp) para {}: {}", correlativo, ex.getMessage());
        }
    }
}
