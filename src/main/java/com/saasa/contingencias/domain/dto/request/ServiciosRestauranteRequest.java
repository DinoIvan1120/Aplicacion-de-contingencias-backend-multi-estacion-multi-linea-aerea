package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * Servicios de un proveedor RESTAURANTE.
 * Corresponde al prototipo imagen 4:
 *   Desayuno S/38 | Almuerzo S/70 | Cena S/75
 */
public record ServiciosRestauranteRequest(
        @DecimalMin(value = "0.00") BigDecimal precioDesayuno,
        @DecimalMin(value = "0.00") BigDecimal precioAlmuerzo,
        @DecimalMin(value = "0.00") BigDecimal precioCena
) {}

