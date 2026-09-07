package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.Min;

import java.time.LocalDate;

/**
 * Request para actualizar los servicios asignados de un voucher.
 *
 * Permite cambiar proveedores, tipos de habitación, servicios de alimentación, etc.
 */
public record ActualizarServiciosRequest(

        // ══════════════════════════════════════════════════════════════════════
        // HOTEL
        // ══════════════════════════════════════════════════════════════════════
        Long hotelVueloRecursoId,           // Puede cambiar de hotel
        String tipoHabitacion,              // SIMPLE, DOBLE, MATRIMONIAL
        @Min(1) Integer cantidadHabitaciones,

        // Servicios de alimentación del hotel
        Boolean hotelDesayuno,
        Boolean hotelAlmuerzo,
        Boolean hotelCena,
        Boolean hotelSnack,
        LocalDate fechaIngreso,    // ← AGREGAR
        LocalDate fechaSalida,     // ← AGREGAR (import java.time.LocalDate)

        // ══════════════════════════════════════════════════════════════════════
        // TRANSPORTE
        // ══════════════════════════════════════════════════════════════════════
        Long transporteVueloRecursoId,      // Puede cambiar de transporte
        String tipoTransporte,              // INDIVIDUAL, GRUPAL, AMBOS
        @Min(1) Integer cantidadPasajeros,

        // ══════════════════════════════════════════════════════════════════════
        // RESTAURANTE
        // ══════════════════════════════════════════════════════════════════════
        Long restauranteVueloRecursoId,     // Puede cambiar de restaurante

        // Servicios del restaurante
        Boolean restauranteDesayuno,
        Boolean restauranteAlmuerzo,
        Boolean restauranteCena,
        Boolean restauranteSnack,
        // Si no existe, agregar:
        Integer cantidadCubiertos
) {

    /**
     * Validación: Al menos un servicio debe estar presente
     */
    public ActualizarServiciosRequest {
        if (hotelVueloRecursoId == null &&
                transporteVueloRecursoId == null &&
                restauranteVueloRecursoId == null) {
            throw new IllegalArgumentException(
                    "Debe proporcionar al menos un servicio (hotel, transporte o restaurante)");
        }
    }
}
