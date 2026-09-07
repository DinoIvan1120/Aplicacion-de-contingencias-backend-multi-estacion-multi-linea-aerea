package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.response.DisponibilidadResponse;
import com.saasa.contingencias.domain.model.VueloRecurso;

/**
 * Servicio para gestionar disponibilidad de recursos en tiempo real
 */
public interface IDisponibilidadService {

    /**
     * Obtiene la disponibilidad actualizada de todos los recursos de un registro diario
     *
     * @param registroVueloDiarioId ID del registro diario
     * @return Disponibilidad de hoteles, transportes y restaurantes
     */
    DisponibilidadResponse obtenerDisponibilidad(Long registroVueloDiarioId);

    /**
     * Notifica a los clientes conectados vía WebSocket sobre cambios en disponibilidad
     *
     * @param registroVueloDiarioId ID del registro diario
     */
    void notificarCambioDisponibilidad(Long registroVueloDiarioId);

    // AÑADIR estos dos métodos a la interfaz existente

    /**
     * Valida que haya habitaciones disponibles del tipo solicitado.
     * Lanza BadRequestException si no hay capacidad suficiente.
     */
    void validarDisponibilidadHotel(VueloRecurso recurso, String tipoHabitacion, int cantidadRequerida);

    /**
     * Valida que haya capacidad disponible para transporte o restaurante.
     * Lanza BadRequestException si no hay capacidad suficiente.
     */
    void validarDisponibilidadGeneral(VueloRecurso recurso, int cantidadRequerida);
}
