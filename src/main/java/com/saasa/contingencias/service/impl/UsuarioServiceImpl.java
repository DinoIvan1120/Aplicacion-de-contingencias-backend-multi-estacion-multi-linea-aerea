package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.*;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.dto.request.AsignarEstacionRequest;
import com.saasa.contingencias.domain.dto.request.UsuarioRequest;
import com.saasa.contingencias.domain.dto.response.UsuarioEstacionResponse;
import com.saasa.contingencias.domain.dto.response.UsuarioResponse;
import com.saasa.contingencias.domain.enumeration.RolEnum;
import com.saasa.contingencias.domain.mapping.UsuarioMapper;
import com.saasa.contingencias.domain.model.Estacion;
import com.saasa.contingencias.domain.model.LineaAerea;
import com.saasa.contingencias.domain.model.Usuario;
import com.saasa.contingencias.domain.model.UsuarioEstacion;
import com.saasa.contingencias.domain.repository.EstacionLineaAereaRepository;
import com.saasa.contingencias.domain.repository.EstacionRepository;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import com.saasa.contingencias.domain.repository.UsuarioEstacionRepository;
import com.saasa.contingencias.domain.repository.UsuarioRepository;
import com.saasa.contingencias.domain.repository.UsuarioSpecification;
import com.saasa.contingencias.service.IUsuarioService;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.saasa.contingencias.config.security.ContextoActivoHolder;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UsuarioServiceImpl implements IUsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final UsuarioMapper usuarioMapper;
    private final UsuarioEstacionRepository usuarioEstacionRepository;
    private final EstacionRepository estacionRepository;
    private final EstacionContext estacionContext;
    private final LineaAereaRepository lineaAereaRepository;
    private final EstacionLineaAereaRepository estacionLineaAereaRepository;

    public UsuarioServiceImpl(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder,
                              UsuarioMapper usuarioMapper, UsuarioEstacionRepository usuarioEstacionRepository,
                              EstacionRepository estacionRepository, EstacionContext estacionContext,
                              LineaAereaRepository lineaAereaRepository,
                              EstacionLineaAereaRepository estacionLineaAereaRepository) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.usuarioMapper = usuarioMapper;
        this.usuarioEstacionRepository = usuarioEstacionRepository;
        this.estacionRepository = estacionRepository;
        this.estacionContext = estacionContext;
        this.lineaAereaRepository = lineaAereaRepository;
        this.estacionLineaAereaRepository = estacionLineaAereaRepository;
    }

    @Override
    public Page<UsuarioResponse> findAll(Pageable pageable) {
        Specification<Usuario> spec = UsuarioSpecification.porContextoAdmin(contextoAdminActivo());
        return usuarioRepository.findAll(spec, pageable).map(usuarioMapper::toResponse);
    }

    /**
     * Resuelve el contexto de trabajo activo (estación+línea) del
     * Administrador que hace la consulta, para acotar qué usuarios puede
     * ver/administrar:
     *   - Administrador Global → null (sin restricción, ve todo).
     *   - Administrador de una sola estación+línea fija → se resuelve solo,
     *     sin depender del selector del topbar.
     *   - Administrador con varias estaciones/líneas → exige el contexto
     *     activo (header X-Estacion-Id / X-Linea-Aerea-Id que manda el
     *     selector del topbar en cada request); línea aérea es opcional
     *     ("todas las líneas" si no selecciona ninguna).
     */
    private ScopeEstacionLinea contextoAdminActivo() {
        if (estacionContext.esAdministradorGlobal()) {
            Long estacionActiva = ContextoActivoHolder.getEstacionId();
            if (estacionActiva == null) return null; // sin contexto activo → sin restricción
            return new ScopeEstacionLinea(estacionActiva, ContextoActivoHolder.getLineaAereaId());
        }
        return estacionContext.resolverContextoActivoLectura();
    }

    /**
     * true si el usuario objetivo comparte el contexto (estación, y línea
     * aérea si el contexto trae una seleccionada) con el Administrador que
     * consulta. "Todas las líneas" en cualquiera de los dos lados cuenta
     * como compatible — mismo criterio que
     * EstacionContext#validarAccesoLectura usa para el resto de entidades.
     */
    private boolean comparteContexto(Usuario objetivo, ScopeEstacionLinea contexto) {
        if (contexto == null) return true; // Administrador Global
        return usuarioEstacionRepository.findActivasByUsuarioId(objetivo.getId()).stream()
                .anyMatch(ue -> ue.getEstacion().getId().equals(contexto.estacionId())
                        && (contexto.lineaAereaId() == null
                        || ue.getLineaAerea() == null
                        || ue.getLineaAerea().getId().equals(contexto.lineaAereaId())));
    }

    private void validarScopeSobreUsuario(Usuario objetivo) {
        ScopeEstacionLinea contexto = contextoAdminActivo();
        if (contexto == null) return; // Administrador Global
        if (!comparteContexto(objetivo, contexto)) {
            throw new AccesoDenegadoException(
                    "No tiene permiso para administrar este usuario: pertenece a otra estación o línea aérea");
        }
    }

    // ─── buscar con filtros dinámicos ──────────────────────────────────────
    @Override
    public Page<UsuarioResponse> buscar(String nombre, String correo,
                                        String documento, String codigoEmpleado,
                                        RolEnum rol, Integer estado,
                                        Pageable pageable) {
        Specification<Usuario> spec = UsuarioSpecification.build(
                nombre, correo, documento, codigoEmpleado, rol, estado);

        // Acota el listado a la estación+línea aérea activa del Administrador
        // que consulta (Administrador Global no recibe restricción alguna).
        Specification<Usuario> filtroContexto = UsuarioSpecification.porContextoAdmin(contextoAdminActivo());
        if (filtroContexto != null) {
            spec = (spec == null) ? filtroContexto : spec.and(filtroContexto);
        }

        // Si todos los filtros son null → spec es null → findAll paginado
        return usuarioRepository.findAll(spec, pageable).map(usuarioMapper::toResponse);
    }

    @Override
    @Transactional
    public UsuarioResponse create(UsuarioRequest request) {
        if (request.correo() != null && !request.correo().isBlank()
                && usuarioRepository.existsByCorreo(request.correo()))
            throw new BadRequestException("Ya existe un usuario con ese correo");
        if (usuarioRepository.existsByCodigoEmpleado(request.codigoEmpleado()))
            throw new BadRequestException("Ya existe un usuario con ese código de empleado");
        if (request.password() == null || request.password().isBlank())
            throw new BadRequestException("La contraseña es obligatoria al crear un usuario");
        Usuario u = Usuario.builder()
                .nombre(request.nombre()).apellido(request.apellido())
                .correo(request.correo()).documento(request.documento())
                .codigoEmpleado(request.codigoEmpleado()).rol(request.rol())
                .passwordHash(passwordEncoder.encode(request.password()))
                .estado(1).build();
        Usuario guardado = usuarioRepository.save(u);

        // Si quien crea NO es Administrador Global, el usuario nuevo queda
        // auto-asignado a la(s) estación(es) del creador. Si el creador
        // tiene línea aérea fija en ese par (Administrador de Estación +
        // Línea Aérea), el nuevo usuario hereda también esa línea; si el
        // creador no tiene línea fija (Administrador de Estación sin
        // restricción de línea), queda sin línea (= todas las líneas de
        // esa estación), igual que antes.
        List<ScopeEstacionLinea> scopesDelCreador = estacionContext.scopesActuales();
        for (ScopeEstacionLinea scope : scopesDelCreador) {
            Estacion estacion = estacionRepository.findById(scope.estacionId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Estación no encontrada: " + scope.estacionId()));
            LineaAerea lineaAerea = null;
            if (scope.lineaAereaId() != null) {
                lineaAerea = lineaAereaRepository.findById(scope.lineaAereaId())
                        .orElseThrow(() -> new RecursoNoEncontradoException(
                                "Línea aérea no encontrada: " + scope.lineaAereaId()));
            }
            usuarioEstacionRepository.save(UsuarioEstacion.builder()
                    .usuario(guardado).estacion(estacion).lineaAerea(lineaAerea).estado(1).build());
        }

        return usuarioMapper.toResponse(guardado);
    }

    @Override
    @Transactional
    public UsuarioResponse update(Long id, UsuarioRequest request) {
        Usuario u = getOrThrow(id);
        // Correo: obligatorio para no-agentes, opcional para AGENTE_SAASA
        String correoNuevo = (request.correo() != null && !request.correo().isBlank())
                ? request.correo() : null;

        if (correoNuevo == null && request.rol() != RolEnum.AGENTE_SAASA)
            throw new BadRequestException("El correo es obligatorio para el rol " + request.rol().name());

        // Unicidad: solo si el correo cambió y no es null
        if (correoNuevo != null && !correoNuevo.equals(u.getCorreo())
                && usuarioRepository.existsByCorreo(correoNuevo))
            throw new BadRequestException("Ya existe un usuario con el correo: " + correoNuevo);
        u.setNombre(request.nombre());
        u.setApellido(request.apellido());
        u.setDocumento(request.documento());
        u.setCodigoEmpleado(request.codigoEmpleado());
        u.setRol(request.rol());
        u.setCorreo(correoNuevo);
        if (request.password() != null && !request.password().isBlank())
            u.setPasswordHash(passwordEncoder.encode(request.password()));
        return usuarioMapper.toResponse(usuarioRepository.save(u));
    }

    @Override
    @Transactional
    public void changeEstado(Long id, Integer estado) {
        Usuario u = getOrThrow(id);
        u.setEstado(estado);
        usuarioRepository.save(u);
    }

    private Usuario getOrThrow(Long id) {
        Usuario u = usuarioRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado: " + id));
        validarScopeSobreUsuario(u);
        return u;
    }

    // ─── Estaciones (+ línea aérea) asignadas al usuario ──────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<UsuarioEstacionResponse> findEstaciones(Long usuarioId) {
        getOrThrow(usuarioId); // valida existencia
        return usuarioEstacionRepository.findByUsuarioId(usuarioId).stream()
                .map(this::toUsuarioEstacionResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public UsuarioEstacionResponse asignarEstacion(Long usuarioId, AsignarEstacionRequest request) {
        Usuario usuario = getOrThrow(usuarioId);

        Estacion estacion = estacionRepository.findById(request.estacionId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Estación no encontrada: " + request.estacionId()));

        // Un admin de estación no puede otorgar una estación fuera de su
        // propio alcance. Administrador Global no tiene restricción.
        List<Long> propias = estacionContext.estacionesActuales();
        if (!propias.isEmpty() && !propias.contains(estacion.getId())) {
            throw new AccesoDenegadoException(
                    "No tiene permiso para asignar la estación " + estacion.getId());
        }

        // Si se manda lineaAereaId, el usuario queda atado a esa única
        // aerolínea dentro de la estación (p. ej. "usuarios solo de Plus
        // Ultra"); null = ve todas las líneas de esa estación. Se valida
        // que la línea, si viene, esté habilitada en esa estación.
        LineaAerea lineaAerea = null;
        if (request.lineaAereaId() != null) {
            lineaAerea = lineaAereaRepository.findById(request.lineaAereaId())
                    .orElseThrow(() -> new RecursoNoEncontradoException(
                            "Línea aérea no encontrada: " + request.lineaAereaId()));
            boolean habilitada = estacionLineaAereaRepository
                    .findByEstacionIdAndLineaAereaId(estacion.getId(), lineaAerea.getId())
                    .map(rel -> rel.getEstado() == 1).orElse(false);
            if (!habilitada) {
                throw new BadRequestException(
                        "La línea aérea " + lineaAerea.getId() + " no está habilitada en la estación " + estacion.getId());
            }
        }

        LineaAerea lineaAereaFinal = lineaAerea;
        UsuarioEstacion relacion = usuarioEstacionRepository
                .findByUsuarioIdAndEstacionIdAndLineaAereaId(usuarioId, estacion.getId(), request.lineaAereaId())
                .map(existente -> {
                    existente.setEstado(1); // reactiva si ya existía inactiva
                    return existente;
                })
                .orElseGet(() -> UsuarioEstacion.builder()
                        .usuario(usuario).estacion(estacion).lineaAerea(lineaAereaFinal).estado(1).build());

        return toUsuarioEstacionResponse(usuarioEstacionRepository.save(relacion));
    }

    @Override
    @Transactional
    public void quitarEstacion(Long usuarioId, Long relacionId) {
        // Desvincular una estación+línea aérea es exclusivo del
        // Administrador Global: un Administrador de Estación puede
        // crear/editar/activar-desactivar usuarios dentro de su propio
        // alcance, pero NO puede desvincular a ningún usuario (ni siquiera
        // a sí mismo) de una relación estación+línea, sea cual sea el
        // usuario objetivo.
        if (!estacionContext.esAdministradorGlobal()) {
            throw new AccesoDenegadoException(
                    "Solo el Administrador Global puede desvincular a un usuario de una estación o línea aérea");
        }
        getOrThrow(usuarioId);
        UsuarioEstacion relacion = usuarioEstacionRepository.findById(relacionId)
                .filter(ue -> ue.getUsuario().getId().equals(usuarioId))
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "El usuario " + usuarioId + " no tiene una relación de estación con id " + relacionId));
        relacion.setEstado(0);
        usuarioEstacionRepository.save(relacion);
    }

    private UsuarioEstacionResponse toUsuarioEstacionResponse(UsuarioEstacion ue) {
        return new UsuarioEstacionResponse(
                ue.getId(), ue.getEstacion().getId(), ue.getEstacion().getCodigoIata(),
                ue.getEstacion().getNombre(),
                ue.getLineaAerea() != null ? ue.getLineaAerea().getId() : null,
                ue.getLineaAerea() != null ? ue.getLineaAerea().getNombre() : null,
                ue.getEstado()
        );
    }
}