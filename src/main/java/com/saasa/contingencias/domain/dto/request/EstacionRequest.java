package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.*;

public record EstacionRequest(
        @NotBlank @Size(min = 3, max = 3, message = "El código IATA debe tener 3 caracteres") String codigoIata,
        @NotBlank @Size(max = 100) String nombre,
        @Size(max = 50) String zonaHoraria
) {}
