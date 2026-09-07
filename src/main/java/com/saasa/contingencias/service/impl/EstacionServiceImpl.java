package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.dto.request.AsignarLineaAereaRequest;
import com.saasa.contingencias.domain.dto.request.EstacionRequest;
import com.saasa.contingencias.domain.dto.response.EstacionLineaAereaResponse;
import com.saasa.contingencias.domain.dto.response.EstacionResponse;
import com.saasa.contingencias.domain.enumeration.EstadoVueloEnum;
import com.saasa.contingencias.domain.mapping.EstacionMapper;
import com.saasa.contingencias.domain.model.Estacion;
import com.saasa.contingencias.domain.model.EstacionLineaAerea;
import com.saasa.contingencias.domain.model.LineaAerea;
import com.saasa.contingencias.domain.repository.EstacionLineaAereaRepository;
import com.saasa.contingencias.domain.repository.EstacionRepository;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import com.saasa.contingencias.domain.repository.VueloRepository;
import com.saasa.contingencias.service.IEstacionService;
import com.saasa.contingencias.service.IS3StorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Módulo Administrador — Gestión de Estaciones y Líneas Aéreas (Fase 2 del
 * cronograma, Documento Funcional Multi-Estación v1.1, sección 5.2).
 */
@Service
public class EstacionServiceImpl implements IEstacionService {

    private final EstacionRepository estacionRepository;
    private final LineaAereaRepository lineaAereaRepository;
    private final EstacionLineaAereaRepository estacionLineaAereaRepository;
    private final EstacionMapper estacionMapper;
    private final IS3StorageService s3StorageService;
    private final VueloRepository vueloRepository;

    public EstacionServiceImpl(EstacionRepository estacionRepository,
                               LineaAereaRepository lineaAereaRepository,
                               EstacionLineaAereaRepository estacionLineaAereaRepository,
                               EstacionMapper estacionMapper,
                               IS3StorageService s3StorageService, VueloRepository vueloRepository) {
        this.estacionRepository = estacionRepository;
        this.lineaAereaRepository = lineaAereaRepository;
        this.estacionLineaAereaRepository = estacionLineaAereaRepository;
        this.estacionMapper = estacionMapper;
        this.s3StorageService = s3StorageService;
        this.vueloRepository = vueloRepository;
    }

    @Override
    public List<EstacionResponse> findAll(Integer estado) {
        List<Estacion> estaciones = estado == null
                ? estacionRepository.findAll()
                : estacionRepository.findAll().stream().filter(e -> e.getEstado().equals(estado)).toList();
        return estacionMapper.toResponseList(estaciones);
    }

    @Override
    public EstacionResponse findById(Long id) {
        return estacionMapper.toResponse(obtenerEstacion(id));
    }

    @Override
    @Transactional
    public EstacionResponse create(EstacionRequest request) {
        String codigo = request.codigoIata().trim().toUpperCase();
        if (estacionRepository.existsByCodigoIata(codigo)) {
            throw new BadRequestException("Ya existe una estación con el código IATA: " + codigo);
        }
        Estacion nueva = Estacion.builder()
                .codigoIata(codigo)
                .nombre(request.nombre().trim())
                .zonaHoraria(request.zonaHoraria() == null || request.zonaHoraria().isBlank()
                        ? "America/Lima" : request.zonaHoraria().trim())
                .estado(1)
                .build();
        return estacionMapper.toResponse(estacionRepository.save(nueva));
    }

    @Override
    @Transactional
    public EstacionResponse update(Long id, EstacionRequest request) {
        Estacion estacion = obtenerEstacion(id);
        String codigo = request.codigoIata().trim().toUpperCase();
        if (!codigo.equals(estacion.getCodigoIata()) && estacionRepository.existsByCodigoIata(codigo)) {
            throw new BadRequestException("Ya existe una estación con el código IATA: " + codigo);
        }
        estacion.setCodigoIata(codigo);
        estacion.setNombre(request.nombre().trim());
        if (request.zonaHoraria() != null && !request.zonaHoraria().isBlank()) {
            estacion.setZonaHoraria(request.zonaHoraria().trim());
        }
        return estacionMapper.toResponse(estacionRepository.save(estacion));
    }

    @Override
    @Transactional
    public void changeEstado(Long id, Integer estado) {
        Estacion estacion = obtenerEstacion(id);
        estacion.setEstado(estado);
        estacionRepository.save(estacion);
        // RN-807: desactivar una estación NO borra su histórico ni sus vínculos
        // con líneas aéreas o usuarios; solo deja de estar disponible para
        // nuevas operaciones (validación de disponibilidad: Fase 3/5).
    }

    @Override
    @Transactional(readOnly = true)
    public List<EstacionLineaAereaResponse> findLineasAereas(Long estacionId, Integer estado) {
        obtenerEstacion(estacionId); // valida existencia
        List<EstacionLineaAerea> relaciones = estacionLineaAereaRepository.findByEstacionId(estacionId);
        if (estado != null) {
            relaciones = relaciones.stream().filter(r -> r.getEstado().equals(estado)).toList();
        }
        // Se arma manualmente (en vez de toRelacionResponseList) porque cada
        // relación necesita su propio conteo de vuelos activos registrados
        // por esa aerolínea en esa estación (badge "N vuelos" del selector).
        return relaciones.stream()
                .map(rel -> {
                    long totalVuelos = vueloRepository.countByEstacionIdAndLineaAereaIdAndEstado(
                            estacionId, rel.getLineaAerea().getId(), EstadoVueloEnum.ACTIVO);
                    return estacionMapper.toRelacionResponse(rel, totalVuelos);
                })
                .toList();
    }
    @Override
    @Transactional
    public EstacionLineaAereaResponse asignarLineaAerea(Long estacionId, AsignarLineaAereaRequest request) {
        Estacion estacion = obtenerEstacion(estacionId);
        LineaAerea lineaAerea = lineaAereaRepository.findById(request.lineaAereaId())
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "Línea aérea no encontrada: " + request.lineaAereaId()));

        EstacionLineaAerea relacion = estacionLineaAereaRepository
                .findByEstacionIdAndLineaAereaId(estacionId, lineaAerea.getId())
                .map(existente -> {
                    existente.setEstado(1); // reactiva si ya existía inactiva
                    return existente;
                })
                .orElseGet(() -> EstacionLineaAerea.builder()
                        .estacion(estacion).lineaAerea(lineaAerea).estado(1).build());

        return estacionMapper.toRelacionResponse(estacionLineaAereaRepository.save(relacion));
    }

    @Override
    @Transactional
    public void cambiarEstadoLineaAerea(Long estacionId, Long lineaAereaId, Integer estado) {
        EstacionLineaAerea relacion = estacionLineaAereaRepository
                .findByEstacionIdAndLineaAereaId(estacionId, lineaAereaId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "La línea aérea " + lineaAereaId + " no está vinculada a la estación " + estacionId));
        relacion.setEstado(estado);
        estacionLineaAereaRepository.save(relacion);
    }

    @Override
    @Transactional
    public EstacionResponse subirFoto(Long id, byte[] fotoBytes, String contentType) {
        Estacion estacion = obtenerEstacion(id);

        String extension = "image/png".equals(contentType) ? ".png" : ".jpg";
        String objectKey = "estaciones-fotos/" + estacion.getCodigoIata() + extension;

        // subirObjeto devuelve la key COMPLETA (con prefijo de ambiente) bajo
        // la que realmente quedó el archivo en S3 — se persiste esa key tal
        // cual, igual que con el logo de línea aérea, para que
        // obtenerUrlFoto() apunte siempre al objeto correcto.
        String keyGuardada = s3StorageService.subirObjeto(fotoBytes, objectKey, contentType);

        estacion.setFotoKey(keyGuardada);
        return estacionMapper.toResponse(estacionRepository.save(estacion));
    }

    @Override
    public String obtenerUrlFoto(Long id) {
        Estacion estacion = obtenerEstacion(id);
        if (estacion.getFotoKey() == null || estacion.getFotoKey().isBlank()) {
            return null;
        }
        return s3StorageService.generarUrlFirmada(estacion.getFotoKey());
    }


    private Estacion obtenerEstacion(Long id) {
        return estacionRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Estación no encontrada: " + id));
    }
}