package com.saasa.contingencias.domain.dto.response;

import java.time.LocalDateTime;

public record EstacionResponse(
        Long id, String codigoIata, String nombre, String zonaHoraria,
        Integer estado,String fotoKey, LocalDateTime createdAt
) {}
