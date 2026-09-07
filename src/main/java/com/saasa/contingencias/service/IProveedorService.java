package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.*;
import com.saasa.contingencias.domain.dto.response.*;
import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import org.springframework.data.domain.*;
import java.util.List;

public interface IProveedorService {
    /**
     * Lista proveedores con filtros opcionales.
     *
     * @param tipo   null → todos los tipos | HOTEL | TRANSPORTE | RESTAURANTE
     * @param estado null → todos | 1 = activos | 0 = inactivos
     */
    Page<ProveedorResponse> findAll(TipoProveedorEnum tipo, Integer estado, Pageable pageable);
    ProveedorResponse create(ProveedorRequest request);
    ProveedorResponse update(Long id, ProveedorRequest request);
    void changeEstado(Long id, Integer estado);
    List<ServicioProveedorResponse> findServicios(Long proveedorId);
    ServicioProveedorResponse addServicio(Long proveedorId, ServicioProveedorRequest request);

    // ── Nuevas operaciones atómicas (prototipo) ───────────────────────────────

    /**
     * Crea un proveedor junto con todos sus servicios tipados en una sola
     * transacción atómica. Si falla la creación de cualquier servicio,
     * se revierte también la creación del proveedor.
     *
     * @param request Body con datos del proveedor + serviciosHotel|serviciosTransporte|serviciosRestaurante
     * @return Proveedor creado con la lista completa de servicios
     */
    ProveedorConServiciosResponse createConServicios(ProveedorConServiciosRequest request);

    /**
     * Actualiza proveedor + servicios en una sola transacción atómica.
     *
     * Estrategia upsert por tipoServicio:
     *   monto > 0  → crea el servicio si no existe / actualiza monto si ya existe
     *   monto = 0  → desactiva el servicio (estado=0) si existe
     *   campo null → ignora ese servicio (no lo toca)
     *
     * El tipo del proveedor NO cambia (es inmutable tras la creación).
     * El RUC NO cambia (identificador fiscal inmutable).
     *
     * @param id      ID del proveedor a actualizar
     * @param request Datos a actualizar (todos opcionales)
     */
    ProveedorConServiciosResponse updateConServicios(Long id,
                                                     ActualizarProveedorConServiciosRequest request);

    /**
     * Devuelve un proveedor con todos sus servicios activos anidados.
     * Útil para poblar la sección "Servicios y Precios Configurados" del prototipo.
     */
    ProveedorConServiciosResponse findByIdConServicios(Long id);
}
