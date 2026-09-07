package com.saasa.contingencias.domain.dto.request;
import jakarta.validation.constraints.*;
/**
 * Request para cambiar la contraseña con el código de verificación.
 *
 * Identificador del usuario: se usa correo O dni (el mismo que se usó en forgot-password).
 * El código siempre es numérico de 6 dígitos (invariante del sistema).
 */
public record ResetPasswordRequest(
    @Email String correo,
    /** DNI del agente — alternativa a correo cuando el login es por documento. */
    @Size(max = 20, message = "El documento no puede superar 20 caracteres")
    String dni,
    @NotBlank @Size(min=6, max=6) @Pattern(regexp="^[0-9]{6}$", message="Código debe ser numérico de 6 dígitos") String codigo,
    @NotBlank @Size(min=8, message="Mínimo 8 caracteres") String nuevaPassword
) {}
