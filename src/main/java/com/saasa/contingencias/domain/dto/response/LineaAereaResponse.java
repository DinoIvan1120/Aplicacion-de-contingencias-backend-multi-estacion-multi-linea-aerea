package com.saasa.contingencias.domain.dto.response;

import java.time.LocalDateTime;

public record LineaAereaResponse(
        Long id, String codigoIata, String nombre, Integer estado, String logoKey, LocalDateTime createdAt
) {}