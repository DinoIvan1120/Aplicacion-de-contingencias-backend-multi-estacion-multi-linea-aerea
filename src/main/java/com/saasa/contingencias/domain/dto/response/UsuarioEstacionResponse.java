package com.saasa.contingencias.domain.dto.response;

/**
 * Estación (+ opcionalmente línea aérea) asignada a un usuario — usada
 * tanto por el módulo de administración (gestionar accesos de un usuario)
 * como para que el propio usuario consulte sus estaciones habilitadas.
 *
 * lineaAereaId null = el usuario ve todas las líneas de esa estación.
 */
public record UsuarioEstacionResponse(
        Long id, Long estacionId, String estacionCodigoIata, String estacionNombre,
        Long lineaAereaId, String lineaAereaNombre, Integer estado
) {}