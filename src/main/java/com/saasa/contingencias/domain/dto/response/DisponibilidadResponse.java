package com.saasa.contingencias.domain.dto.response;

import java.util.List;

/**
 * Response con la disponibilidad actualizada de recursos del día
 */
public record DisponibilidadResponse(
        List<RecursoDisponibleResponse> hoteles,
        List<RecursoDisponibleResponse> transportes,
        List<RecursoDisponibleResponse> restaurantes
) {

    public record RecursoDisponibleResponse(
            Long vueloRecursoId,
            Long proveedorId,
            String proveedorNombre,
            String proveedorTipo,
            String proveedorCorreo,

            // Para hoteles
            Integer habitacionesSimples,
            Integer habitacionesDobles,
            Integer habitacionesMatrimoniales,
            Integer habitacionesSimples_Usadas,
            Integer habitacionesDobles_Usadas,
            Integer habitacionesMatrimoniales_Usadas,
            Integer habitacionesSimples_Disponibles,
            Integer habitacionesDobles_Disponibles,
            Integer habitacionesMatrimoniales_Disponibles,
            Integer totalHabitaciones,
            Integer totalUsadas,
            Integer totalDisponibles,

            // Para transporte/restaurante
            Integer capacidadTotal,
            Integer capacidadUsada,
            Integer capacidadDisponible,

            // Estado
            Boolean agotado
    ) {}
}