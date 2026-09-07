package com.saasa.contingencias.domain.dto.request;
import jakarta.validation.constraints.*;

import java.util.List;

public record EnvioEmailRequest(
        @NotBlank @Email String correoDestino,
        /**
         * TWILIO — Teléfono para reenvío por WhatsApp.
         * Opcional: si es null o vacío se omite el envío WhatsApp.
         * Formato E.164. Ej: "+51987654321"
         */
        @Pattern(regexp = "^\\+[1-9]\\d{6,14}$",
                message = "Teléfono debe estar en formato E.164. Ej: +51987654321")
        String telefono,
        /** NUEVO — correos adicionales (aerolínea/proveedores) a los que también se reenvía el voucher. */
        List<@Email(message = "Correo CC inválido") String> ccDestinos,
        /**
         * NUEVO — Idioma del voucher PDF a generar y enviar ("ES" | "EN").
         * Seleccionado por el agente en el modal "Enviar PDF al Pasajero".
         * Si no se envía, se usa "ES" por defecto.
         */
        @Pattern(regexp = "^(ES|EN)$", message = "idiomaVoucher debe ser ES o EN")
        String idiomaVoucher)

{}
