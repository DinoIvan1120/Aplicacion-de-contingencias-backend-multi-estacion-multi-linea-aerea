package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.NotNull;

public record AsignarLineaAereaRequest(
        @NotNull Long lineaAereaId
) {}
