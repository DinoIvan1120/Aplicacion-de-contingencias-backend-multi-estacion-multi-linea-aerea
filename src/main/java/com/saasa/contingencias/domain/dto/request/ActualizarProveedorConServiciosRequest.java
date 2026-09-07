package com.saasa.contingencias.domain.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;

/**
 * Request para actualizar un proveedor y sus servicios en una sola
 * transacción atómica.
 *
 * Diferencias respecto a ProveedorConServiciosRequest (creación):
 *   - NO incluye 'tipo': el tipo del proveedor NO puede cambiar después
 *     de creado (cambiarlo invalidaría los servicios ya asignados en vuelos).
 *   - NO incluye 'ruc': el RUC es un identificador fiscal inmutable.
 *   - Los campos de datos básicos (nombre, dirección, teléfono, correo)
 *     son todos opcionales — solo se actualizan los que se envíen con valor.
 *
 * Estrategia de actualización de servicios (upsert por tipoServicio):
 *   - monto > 0  → si el servicio existe actualiza monto; si no existe lo crea
 *   - monto = 0  → desactiva el servicio (estado=0) si existe; no hace nada si no existe
 *   - campo null → ignora completamente ese servicio (no lo toca)
 *
 * Ejemplo:
 *   Proveedor HOTEL existente con HABITACION_SIMPLE a S/180 y HOTEL_DESAYUNO a S/35.
 *   Se envía:
 *     precioHabitacionSimple: 200   → actualiza HABITACION_SIMPLE a S/200
 *     precioDesayuno: 0             → desactiva HOTEL_DESAYUNO (estado=0)
 *     precioHabitacionDoble: null   → no toca HABITACION_DOBLE
 *     precioCena: 65                → crea HOTEL_CENA nuevo a S/65
 */
public record ActualizarProveedorConServiciosRequest(

        // ── Datos básicos del proveedor (todos opcionales) ────────────────────────
        String nombre,
        String ruc,           // ← añadir
        String direccion,
        String telefono,

        @Email(message = "El correo no tiene un formato válido")
        String correo,

        // ── Servicios según tipo del proveedor ────────────────────────────────────
        // Solo enviar el bloque que corresponda al tipo del proveedor.
        // El backend ignora los bloques que no correspondan al tipo.
        @Valid ServiciosHotelRequest       serviciosHotel,
        @Valid ServiciosTransporteRequest  serviciosTransporte,
        @Valid ServiciosRestauranteRequest serviciosRestaurante
) {}

