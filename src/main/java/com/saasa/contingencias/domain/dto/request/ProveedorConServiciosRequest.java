package com.saasa.contingencias.domain.dto.request;

import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * Request unificado para crear un proveedor junto con todos sus servicios
 * en una sola transacción atómica.
 *
 * Corresponde al formulario del prototipo (imágenes):
 *   Modal "Nuevo proveedor": tipo, nombre, ruc, dirección, teléfono, correo
 *   + sección "Servicios y Precios Configurados" según el tipo seleccionado:
 *     - HOTEL       → ServiciosHotelRequest
 *     - TRANSPORTE  → ServiciosTransporteRequest
 *     - RESTAURANTE → ServiciosRestauranteRequest
 *
 * Regla de negocio:
 *   - Para HOTEL       → serviciosHotel requerido,  los otros null
 *   - Para TRANSPORTE  → serviciosTransporte requerido, los otros null
 *   - Para RESTAURANTE → serviciosRestaurante requerido, los otros null
 *   La validación cruzada se hace en el Service (no en annotations, para
 *   dar mensajes de error descriptivos).
 */
public record ProveedorConServiciosRequest(

        // ── Datos del proveedor (modal imagen 1) ─────────────────────────────────
        @NotNull(message = "El tipo de proveedor es obligatorio")
        TipoProveedorEnum tipo,

        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        @NotBlank(message = "El RUC es obligatorio")
        @Size(min = 11, max = 11, message = "El RUC debe tener exactamente 11 dígitos")
        @Pattern(regexp = "\\d{11}", message = "El RUC debe contener solo dígitos")
        String ruc,

        String direccion,
        String telefono,

        @Email(message = "El correo no tiene un formato válido")
        String correo,

        // ── Servicios según tipo (sección de precios del prototipo) ──────────────
        @Valid ServiciosHotelRequest       serviciosHotel,
        @Valid ServiciosTransporteRequest  serviciosTransporte,
        @Valid ServiciosRestauranteRequest serviciosRestaurante,

        @NotNull(message = "La línea aérea es obligatoria")
        Long lineaAereaId,

        Long estacionId
) {}
