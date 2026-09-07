package com.saasa.contingencias.domain.dto.request;


import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request de inicio de sesión.
 *
 * Soporta dos modos:
 *  - Estándar (todos los roles):  correo + password
 *  - Agente SAASA:                dni    + password
 *
 * Exactamente uno de (correo, dni) debe estar presente.
 * La validación de esta regla se realiza en AuthServiceImpl.
 *
 * Nota: AuthRequest (correo + password) se mantiene por compatibilidad
 * con los tests existentes y puede coexistir hasta que se migre por completo.
 */
public record LoginRequest(

        @Email(message = "Formato de correo inválido")
        String correo,

        @Size(max = 20, message = "El documento no puede superar 20 caracteres")
        String dni,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, message = "Mínimo 8 caracteres")
        String password

) {}
