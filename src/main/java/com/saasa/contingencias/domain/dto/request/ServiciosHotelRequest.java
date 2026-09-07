package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * Servicios de un proveedor HOTEL.
 * Corresponde exactamente a las imágenes del prototipo:
 *   Habitaciones → Simple S/180, Doble S/250, Matrimonial S/220
 *   Alimentación  → Desayuno S/35, Almuerzo S/55, Snack S/20, Cena S/60
 *
 * Todos los montos son opcionales (BigDecimal), si no se envían
 * se almacenan como ZERO y no se crean como servicio activo.
 */
public record ServiciosHotelRequest(

        // ── Habitaciones ─────────────────────────────────────────────────────────
        @DecimalMin(value = "0.00") BigDecimal precioHabitacionSimple,
        @DecimalMin(value = "0.00") BigDecimal precioHabitacionDoble,
        @DecimalMin(value = "0.00") BigDecimal precioHabitacionMatrimonial,

        // ── Alimentación ─────────────────────────────────────────────────────────
        @DecimalMin(value = "0.00") BigDecimal precioDesayuno,
        @DecimalMin(value = "0.00") BigDecimal precioAlmuerzo,
        @DecimalMin(value = "0.00") BigDecimal precioSnack,
        @DecimalMin(value = "0.00") BigDecimal precioCena
) {}