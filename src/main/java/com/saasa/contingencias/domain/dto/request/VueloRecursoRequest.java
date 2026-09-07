package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.*;

/**
 * Request para habilitar un proveedor (hotel/transporte/restaurante) para un vuelo.
 *
 * Para HOTEL → usar habitacionesSimples, habitacionesDobles, habitacionesMatrimoniales
 *              (corresponde al prototipo: "Seleccione hotel → Hotel Costa del Sol Wyndham
 *               Simples: 10 | Dobles: 20 | Matrimoniales: 4")
 *
 * Para TRANSPORTE → usar capacidadTotal (unidades/vehículos disponibles)
 * Para RESTAURANTE → usar capacidadTotal (cubiertos/mesas disponibles)
 */
public record VueloRecursoRequest(

        @NotNull(message = "El proveedor es obligatorio")
        Long proveedorId,

        // ── Campos para HOTEL (prototipo: 3 tipos de habitación) ─────────────────────────
        @Min(value = 0, message = "Las habitaciones simples no pueden ser negativas")
        Integer habitacionesSimples,

        @Min(value = 0, message = "Las habitaciones dobles no pueden ser negativas")
        Integer habitacionesDobles,

        @Min(value = 0, message = "Las habitaciones matrimoniales no pueden ser negativas")
        Integer habitacionesMatrimoniales,

        // ── Campo para TRANSPORTE y RESTAURANTE ───────────────────────────────────────────
        @Min(value = 0, message = "La capacidad no puede ser negativa")
        Integer capacidadTotal
) {}
