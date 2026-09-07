package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record VoucherGrupalRequest(
        @NotEmpty(message = "Debe indicar al menos una atención") @Size(max = 50)
        List<Long> atencionIds,

        @Email(message = "Email debe ser válido")
        String correoDestino,

        Boolean serviciosCompartidos,

        /** NUEVO — correos adicionales (aerolínea/proveedores) que reciben copia del voucher. */
        List<@Email(message = "Correo CC inválido") String> ccDestinos,

        /**
         * NUEVO — firma digital de conformidad del pasajero titular del grupo:
         * imagen PNG en base64 (data URL) dibujada en el canvas del modal de
         * confirmación. Mismo tope de sanidad que AtencionRequest.firmaPasajero,
         * para blindar este endpoint también cuando se llama directamente
         * (sin pasar antes por el registro inicial de la atención).
         */
        @Size(max = 2_000_000, message = "La firma excede el tamaño máximo permitido")
        String firmaPasajero,

        /**
         * NUEVO — Idioma del voucher PDF a generar ("ES" | "EN"), elegido por el agente en el
         * modal "Confirmar y Enviar Voucher". Si no se envía, se usa "ES" por defecto.
         */
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
         * origenFirma = "AGENTE_LOTE".
         */
        @Pattern(regexp = "^(ADMINISTRADOR|LIDER_SAASA|AGENTE_SAASA)$", message = "origenFirmaRol inválido")
        String origenFirmaRol
) {}
