package com.saasa.contingencias.domain.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response completo que incluye los datos del proveedor
 * y la lista de sus servicios creados, todo en una sola respuesta.
 *
 * El frontend recibe esto al hacer POST /proveedores/con-servicios
 * y puede mostrar directamente la sección "Servicios y Precios Configurados"
 * del prototipo sin necesidad de hacer un segundo GET.
 */
public record ProveedorConServiciosResponse(

        // ── Datos del proveedor ───────────────────────────────────────────────────
        Long id,
        String tipo,
        String nombre,
        String ruc,
        String direccion,
        String telefono,
        String correo,
        Integer estado,
        LocalDateTime createdAt,

        // ── Servicios creados (sección "Servicios y Precios Configurados") ────────
        List<ServicioProveedorResponse> servicios
) {}

