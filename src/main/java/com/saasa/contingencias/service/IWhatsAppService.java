package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;

/**
 * Servicio para envío de notificaciones vía WhatsApp (Twilio).
 *
 * <p>En entorno QAS se usa el Sandbox de Twilio: el pasajero debe haber
 * enviado previamente "join &lt;keyword&gt;" al número de sandbox.
 * En PRD se requiere cuenta aprobada por Meta (WhatsApp Business API).</p>
 */

public interface IWhatsAppService {

    /**
     * Envía el voucher de servicios al pasajero vía WhatsApp.
     *
     * @param telefono       Número del pasajero con código de país. Ej: "+51987654321"
     * @param correlativo    Número correlativo de la atención. Ej: "SGC-000001023"
     * @param nombrePasajero Nombre completo del pasajero
     * @param pdfUrl         URL pública del PDF en S3
     * @param idioma         FIX — idioma elegido para el voucher (ES/EN); el
     *                       mensaje de WhatsApp se genera en ese mismo idioma,
     *                       igual que el correo y el PDF adjunto.
     */

    void enviarVoucherWhatsApp(String telefono, String correlativo,
                               String nombrePasajero, String pdfUrl, IdiomaVoucherEnum idioma);

    /**
     * Reenvío manual del voucher vía WhatsApp (disparado por un agente/líder).
     *
     * @param atencionId ID de la atención existente
     * @param telefono   Número destino (puede diferir del registrado si el agente lo corrige)
     * @param usuarioId  ID del usuario que dispara el reenvío (para auditoría)
     * @param idioma     FIX — idioma elegido para ESTE reenvío. Debe pasarse
     *                   explícitamente en vez de releerlo de BD dentro del
     *                   método @Async: cuando el llamador (p. ej.
     *                   regenerarYEnviarPdf) actualiza atencion.idiomaVoucher
     *                   dentro de una transacción y dispara este método async,
     *                   el hilo async puede ejecutar su propia lectura a BD
     *                   ANTES de que la transacción original haga commit,
     *                   devolviendo el idioma del envío anterior (condición
     *                   de carrera). Si se pasa null, se hace fallback al
     *                   idioma persistido en la Atención (comportamiento
     *                   previo), para no romper llamadores que no lo tengan.
     */
    void reenviarVoucherWhatsApp(Long atencionId, String telefono, Long usuarioId,IdiomaVoucherEnum idioma);
}
