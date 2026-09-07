package com.saasa.contingencias.domain.dto.request;
import jakarta.validation.constraints.Min;
public record RoomSelectionRequest(@Min(0) int simple, @Min(0) int doble, @Min(0) int matrimonial) {}
