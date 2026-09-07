package com.saasa.contingencias.domain.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response completo de un registro de vuelo diario.
 *
 * Incluye toda la información necesaria para que el frontend muestre:
 *   - Los datos del vuelo seleccionado (del itinerario)
 *   - Los recursos habilitados para este registro
 *   - Metadata del registro (quién, cuándo)
 */
public record RegistroVueloDiarioResponse(
        // Datos del registro
        Long id,
        LocalDate fechaRegistro,
        LocalDateTime registradoEn,
        String observaciones,
        Boolean activo,

        // Información del vuelo del itinerario (para mostrar en la card)
        VueloResponse vueloItinerario,

        // Información del líder que registró
        Long registradoPorId,
        String registradoPorNombre,
        String registradoPorCorreo,

        // Recursos habilitados para este registro
        List<VueloRecursoResponse> recursos,

        // Contadores para el resumen (igual que RegistroVueloResponse)
        Integer totalHoteles,
        Integer totalTransportes,
        Integer totalRestaurantes,
        Integer totalHabitaciones
) {
    /**
     * Constructor simplificado sin contadores (se calculan después).
     */
    public RegistroVueloDiarioResponse(
            Long id,
            LocalDate fechaRegistro,
            LocalDateTime registradoEn,
            String observaciones,
            Boolean activo,
            VueloResponse vueloItinerario,
            Long registradoPorId,
            String registradoPorNombre,
            String registradoPorCorreo,
            List<VueloRecursoResponse> recursos
    ) {
        this(
                id, fechaRegistro, registradoEn, observaciones, activo,
                vueloItinerario,
                registradoPorId, registradoPorNombre, registradoPorCorreo,
                recursos,
                calcularTotalHoteles(recursos),
                calcularTotalTransportes(recursos),
                calcularTotalRestaurantes(recursos),
                calcularTotalHabitaciones(recursos)
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Métodos de cálculo de contadores
    // ═══════════════════════════════════════════════════════════════════════

    private static Integer calcularTotalHoteles(List<VueloRecursoResponse> recursos) {
        return (int) recursos.stream()
                .filter(r -> "HOTEL".equals(r.proveedorTipo()))
                .count();
    }

    private static Integer calcularTotalTransportes(List<VueloRecursoResponse> recursos) {
        return (int) recursos.stream()
                .filter(r -> "TRANSPORTE".equals(r.proveedorTipo()))
                .count();
    }

    private static Integer calcularTotalRestaurantes(List<VueloRecursoResponse> recursos) {
        return (int) recursos.stream()
                .filter(r -> "RESTAURANTE".equals(r.proveedorTipo()))
                .count();
    }

    private static Integer calcularTotalHabitaciones(List<VueloRecursoResponse> recursos) {
        return recursos.stream()
                .filter(r -> "HOTEL".equals(r.proveedorTipo()))
                .mapToInt(r -> {
                    int simples = r.habitacionesSimples() != null ? r.habitacionesSimples() : 0;
                    int dobles = r.habitacionesDobles() != null ? r.habitacionesDobles() : 0;
                    int matrimoniales = r.habitacionesMatrimoniales() != null ? r.habitacionesMatrimoniales() : 0;
                    return simples + dobles + matrimoniales;
                })
                .sum();
    }
}
