package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.AsignarLineaAereaRequest;
import com.saasa.contingencias.domain.dto.request.EstacionRequest;
import com.saasa.contingencias.domain.dto.response.EstacionLineaAereaResponse;
import com.saasa.contingencias.domain.dto.response.EstacionResponse;

import java.util.List;

public interface IEstacionService {

    List<EstacionResponse> findAll(Integer estado);

    EstacionResponse findById(Long id);

    EstacionResponse create(EstacionRequest request);

    EstacionResponse update(Long id, EstacionRequest request);

    void changeEstado(Long id, Integer estado);

    /** Líneas aéreas habilitadas en una estación (selector dependiente del login, sección 5.1). */
    List<EstacionLineaAereaResponse> findLineasAereas(Long estacionId, Integer estado);

    /** Habilita una línea aérea (existente en el catálogo) dentro de una estación. */
    EstacionLineaAereaResponse asignarLineaAerea(Long estacionId, AsignarLineaAereaRequest request);

    /** Activa/desactiva una línea aérea dentro de una estación sin borrar el vínculo (RN-807). */
    void cambiarEstadoLineaAerea(Long estacionId, Long lineaAereaId, Integer estado);

    /** Sube/reemplaza la foto del aeropuerto, mostrada en el selector de estación del login. */
    EstacionResponse subirFoto(Long id, byte[] fotoBytes, String contentType);

    /** URL firmada temporal para mostrar la foto del aeropuerto en el frontend. */
    String obtenerUrlFoto(Long id);
}
