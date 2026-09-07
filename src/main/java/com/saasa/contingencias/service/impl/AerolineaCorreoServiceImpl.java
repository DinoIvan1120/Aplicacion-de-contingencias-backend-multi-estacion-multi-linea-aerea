package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.dto.request.AerolineaCorreoRequest;
import com.saasa.contingencias.domain.dto.response.AerolineaCorreoResponse;
import com.saasa.contingencias.domain.mapping.AerolineaCorreoMapper;
import com.saasa.contingencias.domain.model.AerolineaCorreo;
import com.saasa.contingencias.domain.model.LineaAerea;
import com.saasa.contingencias.domain.repository.AerolineaCorreoRepository;
import com.saasa.contingencias.domain.repository.EstacionLineaAereaRepository;
import com.saasa.contingencias.domain.repository.EstacionSpecifications;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import com.saasa.contingencias.service.IAerolineaCorreoService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AerolineaCorreoServiceImpl implements IAerolineaCorreoService {

    private final AerolineaCorreoRepository repository;
    private final AerolineaCorreoMapper mapper;
    private final EstacionContext estacionContext;
    private final LineaAereaRepository lineaAereaRepository;
    private final EstacionLineaAereaRepository estacionLineaAereaRepository;

    public AerolineaCorreoServiceImpl(AerolineaCorreoRepository repository,
                                      AerolineaCorreoMapper mapper,
                                      EstacionContext estacionContext,
                                      LineaAereaRepository lineaAereaRepository,
                                      EstacionLineaAereaRepository estacionLineaAereaRepository) {
        this.repository = repository;
        this.mapper = mapper;
        this.estacionContext = estacionContext;
        this.lineaAereaRepository = lineaAereaRepository;
        this.estacionLineaAereaRepository = estacionLineaAereaRepository;
    }

    // ANTES: listado global, sin relación con la estación/línea del
    // usuario. AHORA: acotado al contexto de trabajo activo — igual que
    // Proveedores — así dentro de un contexto solo existe 0 o 1 correo
    // (garantizado por la unique constraint estacion_id+linea_aerea_id).
    @Override
    @Transactional(readOnly = true)
    public Page<AerolineaCorreoResponse> findAll(Integer estado, Pageable pageable) {
        Specification<AerolineaCorreo> spec = estado != null
                ? (root, query, cb) -> cb.equal(root.get("estado"), estado)
                : null;
        Specification<AerolineaCorreo> filtroContexto = EstacionSpecifications.<AerolineaCorreo>porContextoDelUsuario(
                estacionContext.resolverContextoActivo());
        if (filtroContexto != null) {
            spec = (spec == null) ? filtroContexto : spec.and(filtroContexto);
        }
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AerolineaCorreoResponse findById(Long id) {
        return mapper.toResponse(getOrThrow(id));
    }

    @Override
    @Transactional
    public AerolineaCorreoResponse create(AerolineaCorreoRequest request) {
        // ANTES: la aerolínea era texto libre y la unicidad era global por
        // ese texto (case-insensitive), sin relación con la estación.
        // AHORA: el correo SIEMPRE pertenece al contexto de trabajo activo
        // — un usuario en contexto "Lima · Plus Ultra" no puede crear (ni
        // mandar en el body) el correo de otra estación/línea.
        ScopeEstacionLinea contexto = estacionContext.resolverContextoActivo(
                request.estacionId(), request.lineaAereaId());
        validarLineaAereaHabilitada(contexto.estacionId(), contexto.lineaAereaId());

        if (repository.existsByEstacionIdAndLineaAereaId(contexto.estacionId(), contexto.lineaAereaId())) {
            throw new BadRequestException(
                    "Ya existe un correo parametrizado para esta aerolínea en esta estación");
        }

        // El nombre mostrado se resuelve del catálogo, nunca del texto
        // que mande el cliente: evita duplicados por typo ("LATAM"/"Latam").
        LineaAerea lineaAerea = obtenerLineaAerea(contexto.lineaAereaId());
        AerolineaCorreo entidad = AerolineaCorreo.builder()
                .aerolinea(lineaAerea.getNombre())
                .correo(request.correo().trim())
                .observaciones(request.observaciones())
                .estado(1)
                .build();
        entidad.setEstacionId(contexto.estacionId());
        entidad.setLineaAereaId(contexto.lineaAereaId());
        return mapper.toResponse(repository.save(entidad));
    }

    @Override
    @Transactional
    public AerolineaCorreoResponse update(Long id, AerolineaCorreoRequest request) {
        // La estación y la línea aérea son inmutables tras crear el
        // registro (igual que Proveedor): aquí solo se actualiza correo
        // y observaciones, nunca a qué estación/línea pertenece.
        AerolineaCorreo entidad = getOrThrow(id);
        entidad.setCorreo(request.correo().trim());
        entidad.setObservaciones(request.observaciones());
        return mapper.toResponse(repository.save(entidad));
    }

    @Override
    @Transactional
    public void cambiarEstado(Long id, Integer nuevoEstado) {
        AerolineaCorreo entidad = getOrThrow(id);
        entidad.setEstado(nuevoEstado);
        repository.save(entidad);
    }

    @Override
    @Transactional(readOnly = true)
    public String buscarCorreoPorContexto(Long estacionId, Long lineaAereaId) {
        if (estacionId == null || lineaAereaId == null) return null;
        return repository.findFirstByEstacionIdAndLineaAereaIdAndEstado(estacionId, lineaAereaId, 1)
                .map(AerolineaCorreo::getCorreo)
                .orElse(null);
    }

    private AerolineaCorreo getOrThrow(Long id) {
        AerolineaCorreo entidad = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Correo de aerolínea no encontrado: " + id));
        estacionContext.validarAccesoLectura(entidad);
        return entidad;
    }

    private LineaAerea obtenerLineaAerea(Long lineaAereaId) {
        return lineaAereaRepository.findById(lineaAereaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Línea aérea no encontrada: " + lineaAereaId));
    }

    private void validarLineaAereaHabilitada(Long estacionId, Long lineaAereaId) {
        boolean habilitada = estacionLineaAereaRepository
                .findByEstacionIdAndLineaAereaId(estacionId, lineaAereaId)
                .map(rel -> rel.getEstado() == 1).orElse(false);
        if (!habilitada) {
            throw new BadRequestException("La línea aérea " + lineaAereaId + " no está habilitada en esta estación");
        }
    }
}