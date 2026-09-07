package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.*;

/**
 * ACTUALIZADO — Multi-estación: ya no recibe "aerolinea" como texto
 * libre. El correo se ata a un par estación+línea aérea (mismo patrón
 * que ProveedorRequest): lineaAereaId es obligatorio, estacionId es
 * opcional y se resuelve contra el "contexto de trabajo" activo del
 * usuario (selector de estación/línea del topbar) cuando no se envía.
 */
public record AerolineaCorreoRequest(
        @NotNull(message = "La línea aérea es obligatoria") Long lineaAereaId,
        Long estacionId,
        @NotBlank(message = "El correo es obligatorio") @Email(message = "Correo inválido") String correo,
        @Size(max = 255) String observaciones
) {}
