package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request para actualizar los datos del pasajero de un voucher
 * (nombre, apellido, correo electrónico, teléfono WhatsApp y PNR).
 *
 * Todos los campos son opcionales de forma individual, pero al menos
 * uno debe estar presente. Solo pueden usar este endpoint los roles
 * ADMINISTRADOR y LIDER_SAASA (ver ReporteController).
 */
public record ActualizarPasajeroRequest(

        //Prueba
        @Size(max = 100, message = "El nombre no puede superar los 100 caracteres")
        String nombrePasajero,

        @Size(max = 100, message = "El apellido no puede superar los 100 caracteres")
        String apellidoPasajero,

        @Email(message = "El correo electrónico no tiene un formato válido")
        @Size(max = 150, message = "El correo no puede superar los 150 caracteres")
        String correoPasajero,

        // TWILIO — Formato E.164. Ej: "+51987654321". Se permite null/blank para limpiar el campo.
        @Pattern(regexp = "^$|^\\+[1-9]\\d{6,14}$",
                message = "Teléfono debe estar en formato E.164. Ej: +51987654321")
        String telefonoPasajero,

        @Size(max = 6, message = "El PNR no puede superar los 6 caracteres")
        String pnr,

        // NUEVO — Idioma del voucher PDF: "ES" o "EN". Null = no se toca.
        @Pattern(regexp = "^(ES|EN)$", message = "El idioma debe ser ES o EN")
        String idiomaVoucher
) {

    /**
     * Validación: Al menos un campo debe estar presente.
     */
    public ActualizarPasajeroRequest {
        if (nombrePasajero == null &&
                apellidoPasajero == null &&
                correoPasajero == null &&
                telefonoPasajero == null &&
                pnr == null &&
                idiomaVoucher == null) {
            throw new IllegalArgumentException(
                    "Debe proporcionar al menos un campo a actualizar (nombre, apellido, correo, teléfono o PNR)");
        }
    }
}
