package com.saasa.contingencias.domain.dto.request;
import jakarta.validation.constraints.NotNull;
public record TransportSelectionRequest(@NotNull String tipo) {}
