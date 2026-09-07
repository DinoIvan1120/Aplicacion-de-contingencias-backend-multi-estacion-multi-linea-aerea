package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * Servicios de un proveedor TRANSPORTE.
 * Corresponde al prototipo imagen 3:
 *   Traslado Individual S/65 | Transporte Grupal S/150
 */
public record ServiciosTransporteRequest(
        @DecimalMin(value = "0.00") BigDecimal precioTrasladoIndividual,
        @DecimalMin(value = "0.00") BigDecimal precioTransporteGrupal
) {}
