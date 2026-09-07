package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * lineaAereaId: null = el usuario ve todas las líneas aéreas de esa
 * estación (p. ej. Administrador de Estación sin restricción). No nulo =
 * el usuario queda atado a esa única aerolínea dentro de la estación
 * (p. ej. "usuarios solo de Plus Ultra").
 */
public record AsignarEstacionRequest(
        @NotNull Long estacionId,
        Long lineaAereaId
) {}