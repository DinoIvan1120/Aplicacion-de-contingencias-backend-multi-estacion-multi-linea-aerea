package com.saasa.contingencias.domain.dto.request;

import com.saasa.contingencias.util.AppConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Request para registrar un vuelo del itinerario para el día.
 *
 * El líder SELECCIONA un vuelo ya cargado por el administrador
 * y lo registra con sus recursos para la jornada actual.
 */
public record RegistroVueloDiarioRequest(

        @NotNull(message = "El ID del vuelo del itinerario es obligatorio")
        @Positive(message = "El ID del vuelo debe ser un número positivo")
        Long vueloItinerarioId,

        @NotNull(message = "La fecha de registro es obligatoria")
        LocalDate fechaRegistro,

        @Valid
        List<VueloRecursoRequest> recursos,

        @Size(max = AppConstants.MAX_TEXT_LENGTH, message = "Las observaciones no pueden exceder los 5000 caracteres")
        String observaciones

) {}
