package com.saasa.contingencias.domain.dto.request;
import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import jakarta.validation.constraints.*;
public record ProveedorRequest(
        @NotNull TipoProveedorEnum tipo,
        @NotBlank String nombre,
        @NotBlank @Size(min=11, max=11, message="RUC debe tener 11 dígitos") String ruc,
        String direccion,
        String telefono,
        @Email String correo,
        @NotNull Long lineaAereaId,
        Long estacionId
) {}
