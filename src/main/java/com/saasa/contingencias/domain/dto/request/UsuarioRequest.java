package com.saasa.contingencias.domain.dto.request;
import com.saasa.contingencias.domain.enumeration.RolEnum;
import jakarta.validation.constraints.*;
public record UsuarioRequest(
    @NotBlank String nombre,
    @NotBlank String apellido,
    /**
     * Opcional para AGENTE_SAASA. Si se proporciona, debe tener formato email válido.
     * Para el resto de roles sigue siendo obligatorio (la validación se hace en el service).
     */
    @Email(message = "Formato de correo inválido") String correo,
    @NotBlank String documento,
    @NotBlank @Size(max = 150) String codigoEmpleado,
    @NotNull RolEnum rol,
    @Size(min=8) @Pattern(regexp="^(?=.*[A-Z])(?=.*\\d).{8,}$", message="Mínimo 8 chars, una mayúscula y un número") String password
) {}
