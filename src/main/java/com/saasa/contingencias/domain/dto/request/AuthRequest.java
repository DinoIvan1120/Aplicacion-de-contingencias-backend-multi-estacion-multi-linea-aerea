package com.saasa.contingencias.domain.dto.request;
import jakarta.validation.constraints.*;
public record AuthRequest(
    @NotBlank(message = "El correo es obligatorio") @Email(message = "Formato de correo inválido") String correo,
    @NotBlank(message = "La contraseña es obligatoria") @Size(min = 8, message = "Mínimo 8 caracteres") String password
) {}
