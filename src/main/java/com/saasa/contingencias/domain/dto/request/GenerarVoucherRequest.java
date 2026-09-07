package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request para generación y envío de voucher PDF.
 *
 * @param correoDestino Email donde se enviará el voucher (solo para generar-y-enviar)
 * @param ccDestinos NUEVO — correos adicionales (aerolínea/proveedores parametrizados en el
 *                   administrador) a los que también se les envía copia del voucher.
 * @param firmaPasajero NUEVO — firma digital del pasajero: imagen PNG en base64 (data URL)
 *                      dibujada en el canvas de conformidad del modal de confirmación del
 *                      agente. Tope de sanidad de 2_000_000 caracteres, igual que
 *                      AtencionRequest.firmaPasajero, para blindar este endpoint también
 *                      cuando se llama directamente (sin pasar antes por el registro
 *                      inicial de la atención).
 * @param idiomaVoucher NUEVO — Idioma del voucher PDF a generar ("ES" | "EN"), elegido por el
 *                      agente en el modal "Confirmar y Enviar Voucher". Si no se envía, se usa
 *                      "ES" por defecto.
 */
public record GenerarVoucherRequest(
        @Email(message = "Email debe ser válido")
        String correoDestino,
        List<@Email(message = "Correo CC inválido") String> ccDestinos,
        @Size(max = 2_000_000, message = "La firma excede el tamaño máximo permitido")
        String firmaPasajero,
        @Pattern(regexp = "^(ES|EN)$", message = "idiomaVoucher debe ser ES o EN")
        String idiomaVoucher,
        /**
         * NUEVO — Origen de firmaPasajero ("PASAJERO" | "AGENTE_LOTE").
         * Si no se envía, se asume "PASAJERO" (comportamiento previo).
         */
        @Pattern(regexp = "^(PASAJERO|AGENTE_LOTE)$", message = "origenFirma debe ser PASAJERO o AGENTE_LOTE")
        String origenFirma,

        /**
         * NUEVO — Rol real (ADMINISTRADOR | LIDER_SAASA | AGENTE_SAASA) del
         * usuario que autorizó la carga masiva, solo aplica cuando
         * origenFirma = "AGENTE_LOTE". Se usa para que el reporte muestre
         * el rol correcto en vez de un texto fijo de "agente".
         */
        @Pattern(regexp = "^(ADMINISTRADOR|LIDER_SAASA|AGENTE_SAASA)$", message = "origenFirmaRol inválido")
        String origenFirmaRol
) {}
