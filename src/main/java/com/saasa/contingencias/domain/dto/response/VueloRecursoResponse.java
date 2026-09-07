package com.saasa.contingencias.domain.dto.response;

import java.time.LocalDateTime;

/**
 * Response del recurso habilitado para un vuelo.
 *
 * Para HOTEL devuelve los 3 tipos de habitación separados + totalHabitaciones,
 * que es lo que necesita el prototipo para mostrar el resumen:
 *   "Resumen de Capacidad: 10 Simples | 20 Dobles | 4 Matrimoniales"
 */
public record VueloRecursoResponse(
        Long id,
        Long vueloId,
        Long proveedorId,
        String proveedorNombre,
        String proveedorTipo,
        String proveedorCorreo,

        // ── Campos hotel ──────────────────────────────────────────────────────────────────
        Integer habitacionesSimples,
        Integer habitacionesDobles,
        Integer habitacionesMatrimoniales,
        Integer totalHabitaciones,          // suma de los 3 tipos (calculado)

        // ── Campo transporte/restaurante ──────────────────────────────────────────────────
        Integer capacidadTotal,

        String habilitadoPorNombre,
        LocalDateTime habilitadoEn,
        Integer estado
) {}
