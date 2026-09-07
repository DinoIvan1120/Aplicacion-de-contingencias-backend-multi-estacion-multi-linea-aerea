package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.ServicioAsignadoRequest;
import com.saasa.contingencias.domain.model.VueloRecurso;

import java.math.BigDecimal;

/**
 * Servicio de cálculo de precios de servicios de proveedores.
 *
 * Responsabilidad única: dado un recurso habilitado y un request,
 * determinar el monto económico correspondiente consultando ServicioProveedor.
 *
 * Extraído de AtencionServiceImpl y ReporteServiceImpl donde existía
 * lógica de precios duplicada.
 */
public interface IServicioMontoService {

    /**
     * Calcula el monto unitario de un servicio según tipo de proveedor.
     * Cubre HOTEL (habitación + alimentación), TRANSPORTE y RESTAURANTE.
     *
     * @param vueloRecurso Recurso habilitado que contiene el proveedor
     * @param req          Request con tipo de detalle, habitación, comidas, etc.
     * @return Monto calculado. Devuelve BigDecimal.ZERO si no hay precio configurado.
     */
    BigDecimal calcularMonto(VueloRecurso vueloRecurso, ServicioAsignadoRequest req);

    /**
     * Busca el precio de un servicio específico de un proveedor.
     * Devuelve BigDecimal.ZERO si el servicio no existe o está inactivo.
     *
     * @param proveedorId  ID del proveedor
     * @param tipoServicio Clave del servicio (ej: "HABITACION_SIMPLE", "HOTEL_DESAYUNO")
     * @return Precio del servicio o ZERO
     */
    BigDecimal buscarPrecio(Long proveedorId, String tipoServicio);
}

