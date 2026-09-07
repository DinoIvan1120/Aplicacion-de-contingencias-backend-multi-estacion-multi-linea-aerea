package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.dto.request.*;
import com.saasa.contingencias.domain.dto.response.*;
import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import com.saasa.contingencias.service.IProveedorService;
import com.saasa.contingencias.util.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/proveedores")
@Tag(name = "Proveedores")
public class ProveedorController {

    private final IProveedorService proveedorService;

    public ProveedorController(IProveedorService proveedorService) { this.proveedorService = proveedorService; }

    // ─── GET /proveedores ─────────────────────────────────────────────────────
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','LINEA_AEREA','PROVEEDOR')")
    @Operation(
            summary = "Lista proveedores con filtros opcionales por tipo y estado",
            description = "tipo: HOTEL|TRANSPORTE|RESTAURANTE (opcional). estado: 1=activos, 0=inactivos (opcional)")
    public ResponseEntity<ApiResponse<Page<ProveedorResponse>>> findAll(
            @RequestParam(required = false) TipoProveedorEnum tipo,
            @RequestParam(required = false) Integer estado,
            @PageableDefault(size = 20, sort = "nombre") Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                proveedorService.findAll(tipo, estado, pageable)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    public ResponseEntity<ApiResponse<ProveedorResponse>> create(@Valid @RequestBody ProveedorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(proveedorService.create(request)));
    }

    // ─── POST /proveedores/con-servicios — operación atómica del prototipo ────
    /**
     * NUEVO ENDPOINT — Crea proveedor + servicios tipados en una sola llamada.
     *
     * Este endpoint corresponde exactamente al flujo del frontend (prototipo):
     *   1. El usuario llena el modal "Nuevo proveedor" (tipo, nombre, ruc, etc.)
     *   2. Según el tipo seleccionado aparece la sección de precios:
     *      - HOTEL       → habitaciones (simple/doble/matrimonial) + alimentación
     *      - TRANSPORTE  → Aeropuerto-Domicilio + Domicilio-Aeropuerto
     *      - RESTAURANTE → desayuno + almuerzo + cena
     *   3. Al hacer click en "Crear" se envía TODO en una sola llamada a este endpoint.
     *   4. La respuesta incluye el proveedor creado + todos sus servicios.
     */
    @PostMapping("/con-servicios")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(
            summary = "Crea un proveedor junto con todos sus servicios y precios en una sola transacción",
            description = """
            Operación atómica: si falla la creación de cualquier servicio,
            también se revierte la creación del proveedor.

            Según el tipo enviado, incluir el bloque correspondiente:

            **HOTEL** → serviciosHotel:
              precioHabitacionSimple, precioHabitacionDoble, precioHabitacionMatrimonial,
              precioDesayuno, precioAlmuerzo, precioSnack, precioCena

            **TRANSPORTE** → serviciosTransporte:
            precioTrasladoIndividual (Aeropuerto-Domicilio), precioTransporteGrupal (Domicilio-Aeropuerto)

            **RESTAURANTE** → serviciosRestaurante:
              precioDesayuno, precioAlmuerzo, precioCena

            Solo se crean los servicios cuyo monto sea > 0.
            Debe enviarse al menos un servicio con monto > 0 por tipo.
            """)
    public ResponseEntity<ApiResponse<ProveedorConServiciosResponse>> createConServicios(
            @Valid @RequestBody ProveedorConServiciosRequest request) {
        ProveedorConServiciosResponse response = proveedorService.createConServicios(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Proveedor '" + response.nombre() + "' creado con " +
                                response.servicios().size() + " servicio(s) configurado(s)",
                        response));
    }

    // ─── PUT /proveedores/{id}/con-servicios — actualización atómica (NUEVO) ──
    /**
     * Actualiza datos básicos del proveedor y sus servicios en una sola
     * transacción atómica usando estrategia upsert por tipoServicio.
     *
     * Estrategia de actualización de cada servicio:
     *   monto > 0  → crea si no existe / actualiza y reactiva si existía
     *   monto = 0  → desactiva el servicio (estado=0) si existe
     *   campo null → no toca ese servicio (actualización parcial)
     *
     * Restricciones:
     *   El tipo del proveedor NO se puede cambiar (es inmutable).
     *   El RUC NO se puede cambiar (identificador fiscal inmutable).
     */
    @PutMapping("/{id}/con-servicios")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(
            summary = "Actualiza proveedor + servicios en una sola transacción atómica",
            description = """
            Actualiza nombre, dirección, teléfono, correo y servicios con sus precios.
            El tipo y el RUC son inmutables — no se incluyen en este endpoint.

            **Estrategia upsert por tipoServicio:**
            - monto > 0  → si existe: actualiza precio y reactiva; si no: lo crea nuevo
            - monto = 0  → si existe: desactiva (estado=0); si no: ignora
            - campo null → no toca ese servicio (permite actualizaciones parciales)

            **Ejemplo — HOTEL:** actualizar solo el precio de la habitación simple
            y desactivar el snack sin tocar los demás servicios:
            ```json
            {
              "serviciosHotel": {
                "precioHabitacionSimple": 200.00,
                "precioSnack": 0,
                "precioHabitacionDoble": null,
                "precioHabitacionMatrimonial": null,
                "precioDesayuno": null,
                "precioAlmuerzo": null,
                "precioCena": null
              }
            }
            ```

            **Para HOTEL**     → enviar `serviciosHotel`
            **Para TRANSPORTE** → enviar `serviciosTransporte`
            **Para RESTAURANTE** → enviar `serviciosRestaurante`
            """)
    public ResponseEntity<ApiResponse<ProveedorConServiciosResponse>> updateConServicios(
            @PathVariable Long id,
            @Valid @RequestBody ActualizarProveedorConServiciosRequest request) {
        ProveedorConServiciosResponse response = proveedorService.updateConServicios(id, request);
        return ResponseEntity.ok(ApiResponse.success(
                "Proveedor '" + response.nombre() + "' actualizado con " +
                        response.servicios().size() + " servicio(s) activo(s)",
                response));
    }


    // ─── GET /proveedores/{id}/con-servicios — proveedor + servicios ──────────
    @GetMapping("/{id}/con-servicios")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    @Operation(summary = "Obtiene un proveedor con todos sus servicios y precios activos")
    public ResponseEntity<ApiResponse<ProveedorConServiciosResponse>> findByIdConServicios(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                proveedorService.findByIdConServicios(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    public ResponseEntity<ApiResponse<ProveedorResponse>> update(@PathVariable Long id, @Valid @RequestBody ProveedorRequest request) {
        return ResponseEntity.ok(ApiResponse.success(proveedorService.update(id, request)));
    }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    public ResponseEntity<ApiResponse<Void>> changeEstado(@PathVariable Long id, @RequestParam Integer estado) {
        proveedorService.changeEstado(id, estado);
        return ResponseEntity.ok(ApiResponse.success("Estado actualizado", null));
    }

    @GetMapping("/{id}/servicios")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    public ResponseEntity<ApiResponse<List<ServicioProveedorResponse>>> findServicios(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(proveedorService.findServicios(id)));
    }

    @PostMapping("/{id}/servicios")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    public ResponseEntity<ApiResponse<ServicioProveedorResponse>> addServicio(@PathVariable Long id,
            @Valid @RequestBody ServicioProveedorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(proveedorService.addServicio(id, request)));
    }
}
