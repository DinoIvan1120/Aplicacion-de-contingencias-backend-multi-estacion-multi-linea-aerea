package com.saasa.contingencias.domain.dto.request;

import com.saasa.contingencias.domain.enumeration.ContingenciaEnum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Request unificado para la vista completa del Líder.
 *
 * Cubre todo lo que muestra el prototipo en UNA SOLA vista:
 *
 *   SECCIÓN 1 — Registro de Información de Vuelo (imágenes 1 y 2):
 *     aerolinea, codigoVuelo, origen, destino, fechaVuelo, horaVuelo,
 *     tipoContingencia, observaciones
 *
 *   SECCIÓN 2 — Habilitar Recursos para el Día (imágenes 3-9):
 *     Lista de recursos: hoteles (simples/dobles/matrimoniales),
 *     transportes y restaurantes seleccionados por el Líder.
 *
 * El botón "Guardar Información de Vuelo" (imagen 8/9) envía TODO este body.
 *
 * Para CREAR: POST /api/v1/vuelos/registro
 * Para ACTUALIZAR: PUT /api/v1/vuelos/{id}/registro
 */
public record RegistroVueloRequest(

        // ── Sección 1: Datos del vuelo ────────────────────────────────────────────
        @NotBlank(message = "La aerolínea es obligatoria")
        String aerolinea,

        @NotBlank(message = "El número de vuelo es obligatorio")
        String codigoVuelo,

        @NotBlank
        @Size(min = 3, max = 3, message = "El código IATA de origen debe tener 3 letras")
        @Pattern(regexp = "^[A-Z]{3}$", message = "Código IATA origen inválido (ej: LIM, MAD, BOG)")
        String origen,

        @NotBlank
        @Size(min = 3, max = 3, message = "El código IATA de destino debe tener 3 letras")
        @Pattern(regexp = "^[A-Z]{3}$", message = "Código IATA destino inválido")
        String destino,

        @NotNull(message = "La fecha del vuelo es obligatoria")
        LocalDate fechaVuelo,

        /** Hora en formato HH:mm (ej: "10:06"). Solo informativo, no afecta lógica. */
        String horaVuelo,

        @NotNull(message = "El tipo de contingencia es obligatorio")
        ContingenciaEnum tipoContingencia,

        /** Estado del vuelo desde el selector del prototipo: Programado, Reprogramado, Cancelado, Demorado. */
        String observaciones,

        // ── Sección 2: Recursos habilitados ──────────────────────────────────────
        /**
         * Lista de recursos que el Líder seleccionó para este vuelo.
         * Puede estar vacía si el Líder aún no habilitó recursos.
         *
         * Cada elemento es un VueloRecursoRequest que contiene:
         *   - proveedorId (obligatorio)
         *   - habitacionesSimples, habitacionesDobles, habitacionesMatrimoniales (para HOTEL)
         *   - capacidadTotal (para TRANSPORTE o RESTAURANTE)
         *
         * Comportamiento (upsert): si el recurso ya existe para este vuelo,
         * se actualiza; si no existe, se crea.
         */
        @Valid
        List<VueloRecursoRequest> recursos,

        Long estacionId,
        // Contexto de trabajo activo (topbar), igual que en VueloRequest.
        Long lineaAereaId
) {}
