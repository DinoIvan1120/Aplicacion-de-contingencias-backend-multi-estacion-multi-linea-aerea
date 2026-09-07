package com.saasa.contingencias.domain.dto.response;

import java.time.LocalDateTime;

public record AerolineaCorreoResponse(
        Long id,
        Long estacionId,
        Long lineaAereaId,
        String aerolinea,
        String correo,
        String observaciones,
        Integer estado,
        LocalDateTime createdAt
) {}
