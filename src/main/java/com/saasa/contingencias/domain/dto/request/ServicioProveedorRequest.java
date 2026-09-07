package com.saasa.contingencias.domain.dto.request;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public record ServicioProveedorRequest(
    @NotBlank String tipoServicio,
    String descripcion,
    @NotNull @DecimalMin(value="0.01", message="El monto debe ser mayor a 0") BigDecimal monto
) {}
