package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.ActualizarServiciosRequest;
import com.saasa.contingencias.domain.dto.response.ReporteDetalleResponse;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.model.ServicioAsignado;

import java.math.BigDecimal;

/**
 * Servicio de construcción de detalles de servicios para reportes.
 *
 * Responsabilidad única: dado un ServicioAsignado o un request de actualización,
 * construir el DTO de detalle o persistir el nuevo servicio asignado.
 *
 * Extraído de ReporteServiceImpl donde convivía con la lógica de
 * consulta, exportación y envío de PDFs.
 */
public interface IReporteServicioBuilder {

    /**
     * Construye el detalle de un servicio de hotel para el reporte.
     *
     * @param servicio ServicioAsignado de tipo HOTEL
     * @return DTO con habitación, alimentación y precios
     */
    ReporteDetalleResponse.ServicioDetalleResponse buildHotelDetalle(ServicioAsignado servicio);

    /**
     * Construye el detalle de un servicio de transporte para el reporte.
     *
     * @param servicio ServicioAsignado de tipo TRANSPORTE
     * @return DTO con tipo de transporte y monto
     */
    ReporteDetalleResponse.ServicioDetalleResponse buildTransporteDetalle(ServicioAsignado servicio);

    /**
     * Construye el detalle de un servicio de restaurante para el reporte.
     *
     * @param servicio ServicioAsignado de tipo RESTAURANTE
     * @return DTO con comidas y precios
     */
    ReporteDetalleResponse.ServicioDetalleResponse buildRestauranteDetalle(ServicioAsignado servicio);

    /**
     * Crea y persiste un nuevo ServicioAsignado de hotel.
     * Usado en actualizarServicios para reemplazar los servicios anteriores.
     *
     * @param atencion Atención a la que se asocia el servicio
     * @param request  Request con los datos del hotel
     * @return Monto total calculado para el hotel
     */
    BigDecimal crearServicioHotel(Atencion atencion, ActualizarServiciosRequest request);

    /**
     * Crea y persiste un nuevo ServicioAsignado de transporte.
     *
     * @param atencion Atención a la que se asocia el servicio
     * @param request  Request con los datos del transporte
     * @return Monto total calculado para el transporte
     */
    BigDecimal crearServicioTransporte(Atencion atencion, ActualizarServiciosRequest request);

    /**
     * Crea y persiste un nuevo ServicioAsignado de restaurante.
     *
     * @param atencion Atención a la que se asocia el servicio
     * @param request  Request con los datos del restaurante
     * @return Monto total calculado para el restaurante
     */
    BigDecimal crearServicioRestaurante(Atencion atencion, ActualizarServiciosRequest request);
}
