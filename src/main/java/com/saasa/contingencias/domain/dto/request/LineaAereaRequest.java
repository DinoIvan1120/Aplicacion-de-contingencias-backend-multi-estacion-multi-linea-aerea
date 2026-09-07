package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.*;

public record LineaAereaRequest(
        @NotBlank @Size(min = 2, max = 3, message = "El código IATA debe tener 2 o 3 caracteres") String codigoIata,
        @NotBlank @Size(max = 100) String nombre
) {}
