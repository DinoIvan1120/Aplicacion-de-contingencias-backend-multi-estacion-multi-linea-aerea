package com.saasa.contingencias.domain.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response unificado de la vista del Líder.
 *
 * Devuelve el vuelo + todos sus recursos habilitados en una sola respuesta.
 * Esto permite que el frontend cargue toda la vista con UNA sola llamada.
 *
 * Usado en:
 *   GET /api/v1/vuelos/{id}/registro  → cargar la vista con datos existentes
 *   POST /api/v1/vuelos/registro      → respuesta tras crear
 *   PUT /api/v1/vuelos/{id}/registro  → respuesta tras actualizar (botón Guardar)
 *
 * El frontend usa los campos de resumen para mostrar la sección
 * "Resumen de Recursos Habilitados" (imagen 9):
 *   Hoteles (1): Hotel Costa del Sol — 2 Simples | 3 Dobles | 2 Matrimoniales
 *   Transportes (2): Transportes Rápidos SAC, Transporte Express Callao
 *   Restaurantes (2): Restaurante El Bolivariano, Central Restaurante
 */
public record RegistroVueloResponse(

        // ── Datos del vuelo ───────────────────────────────────────────────────────
        Long id,
        String aerolinea,
        String codigoVuelo,
        String origen,
        String destino,
        LocalDate fechaVuelo,
        String horaVuelo,
        String tipoContingencia,
        String observaciones,
        String estado,
        Long creadoPorId,
        String creadoPorNombre,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,

        // ── Recursos habilitados (sección inferior del prototipo) ─────────────────
        /** Todos los recursos activos habilitados para este vuelo. */
        List<VueloRecursoResponse> recursos,

        // ── Contadores para el resumen (imagen 9) ─────────────────────────────────
        int totalHoteles,
        int totalTransportes,
        int totalRestaurantes
) {}