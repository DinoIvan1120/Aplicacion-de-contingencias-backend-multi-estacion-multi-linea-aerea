package com.saasa.contingencias.domain.dto.request;
import jakarta.validation.constraints.*;

/**
 * Request de recuperación de contraseña.
 *
 * Dos modos mutuamente excluyentes:
 *  - correo: flujo estándar (todos los roles).
 *  - dni:    flujo para AGENTE_SAASA que inicia sesión por documento.
 *
 * La validación de que exactamente uno esté presente se hace en AuthServiceImpl.
 */
public record ForgotPasswordRequest(
    @Email(message = "Formato de correo inválido") String correo,

    /**
     * DNI / documento del agente. Solo se usa cuando correo es null/blank.
     * Longitud máxima 20 (igual que la columna documento en usuarios).
     */
    @Size(max = 20, message = "El documento no puede superar 20 caracteres")
            String dni
) {}
