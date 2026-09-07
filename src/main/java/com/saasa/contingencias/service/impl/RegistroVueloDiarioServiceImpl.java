package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.dto.request.RegistroVueloDiarioRequest;
import com.saasa.contingencias.domain.dto.request.VueloRecursoRequest;
import com.saasa.contingencias.domain.dto.response.CapacidadComprometidaResponse;
import com.saasa.contingencias.domain.dto.response.RegistroVueloDiarioResponse;
import com.saasa.contingencias.domain.mapping.RegistroVueloDiarioMapper;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.service.IRegistroVueloDiarioService;
import com.saasa.contingencias.util.DateTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@Transactional
public class RegistroVueloDiarioServiceImpl implements IRegistroVueloDiarioService {

    private final RegistroVueloDiarioRepository registroRepository;
    private final VueloRepository vueloRepository;
    private final UsuarioRepository usuarioRepository;
    private final ProveedorRepository proveedorRepository;
    private final VueloRecursoRepository vueloRecursoRepository;
    private final RegistroVueloDiarioMapper mapper;
    private final com.saasa.contingencias.config.security.EstacionContext estacionContext;

    public RegistroVueloDiarioServiceImpl(
            RegistroVueloDiarioRepository registroRepository,
            VueloRepository vueloRepository,
            UsuarioRepository usuarioRepository,
            ProveedorRepository proveedorRepository,
            VueloRecursoRepository vueloRecursoRepository,
            RegistroVueloDiarioMapper mapper,
            com.saasa.contingencias.config.security.EstacionContext estacionContext
    ) {
        this.registroRepository = registroRepository;
        this.vueloRepository = vueloRepository;
        this.usuarioRepository = usuarioRepository;
        this.proveedorRepository = proveedorRepository;
        this.vueloRecursoRepository = vueloRecursoRepository;
        this.mapper = mapper;
        this.estacionContext = estacionContext;
    }

    @Override
    public RegistroVueloDiarioResponse registrarVuelo(RegistroVueloDiarioRequest request, Long liderId) {
        log.info("Iniciando registro de vuelo itinerario {} para fecha {} por líder {}",
                request.vueloItinerarioId(), request.fechaRegistro(), liderId);

        // 1. Validar que el vuelo del itinerario existe
        Vuelo vueloItinerario = vueloRepository.findById(request.vueloItinerarioId())
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "Vuelo del itinerario no encontrado con ID: " + request.vueloItinerarioId()));

        // 2. Validar que el líder existe
        Usuario lider = usuarioRepository.findById(liderId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Líder no encontrado con ID: " + liderId));

        // 3. Validar que no esté duplicado en la misma fecha
        if (registroRepository.existsByVueloItinerarioIdAndFechaRegistroAndActivoTrue(
                request.vueloItinerarioId(), request.fechaRegistro())) {
            throw new IllegalStateException(
                    "El vuelo " + vueloItinerario.getCodigoVuelo() +
                            " ya está registrado para la fecha " + request.fechaRegistro());
        }

        // 4. Crear el registro
        RegistroVueloDiario registro = RegistroVueloDiario.builder()
                .vueloItinerario(vueloItinerario)
                .registradoPor(lider)
                .fechaRegistro(request.fechaRegistro())
                .registradoEn(DateTimeUtil.ahoraEnLima())
                .observaciones(request.observaciones())
                .active(true)
                .build();
        // El registro hereda estación/línea aérea del vuelo de itinerario
        registro.setEstacionId(vueloItinerario.getEstacionId());
        registro.setLineaAereaId(vueloItinerario.getLineaAereaId());

        // 5. Guardar el registro (primero para obtener el ID)
        registro = registroRepository.save(registro);
        log.info("Registro creado con ID: {}", registro.getId());

        // 6. Habilitar recursos si se especificaron
        if (request.recursos() != null && !request.recursos().isEmpty()) {
            habilitarRecursos(registro, request.recursos(), lider);
        }

        // 7. Recargar para obtener recursos asociados
        registro = registroRepository.findById(registro.getId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Error al recargar registro"));

        log.info("Vuelo {} registrado exitosamente con {} recursos",
                vueloItinerario.getCodigoVuelo(), registro.getRecursos().size());

        return mapper.toResponse(registro);
    }

    @Override
    @Transactional(readOnly = true)
    public RegistroVueloDiarioResponse obtenerPorId(Long id) {
        RegistroVueloDiario registro = getOrThrow(id);
        return mapper.toResponse(registro);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RegistroVueloDiarioResponse> obtenerRegistrosDelDia() {
        LocalDate hoy = DateTimeUtil.hoyEnLima();
        LocalDate ayer = hoy.minusDays(1);
        log.info("Obteniendo registros entre {} y {} (ventana 24h+)", ayer, hoy);

        var contexto = estacionContext.resolverContextoActivoLectura();
        List<RegistroVueloDiario> registros = registroRepository
                .findByFechaRegistroEntreYActivoTrueList(
                        ayer, hoy, contexto.estacionId(), contexto.lineaAereaId());

        log.info("Encontrados {} registros (ventana 24h+)", registros.size());
        return mapper.toResponseList(registros);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RegistroVueloDiarioResponse> obtenerPorRangoFechas(
            LocalDate fechaInicio,
            LocalDate fechaFin,
            Pageable pageable
    ) {
        log.info("Obteniendo registros entre {} y {}", fechaInicio, fechaFin);

        var contexto = estacionContext.resolverContextoActivoLectura();
        Page<RegistroVueloDiario> registros = registroRepository
                .findByFechaRegistroBetweenAndActivoTrue(
                        fechaInicio, fechaFin, contexto.estacionId(), contexto.lineaAereaId(), pageable);

        return registros.map(mapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RegistroVueloDiarioResponse> obtenerMisRegistros(
            Long liderId,
            LocalDate fechaInicio,
            LocalDate fechaFin,
            Pageable pageable
    ) {
        log.info("Obteniendo registros del líder {} entre {} y {}", liderId, fechaInicio, fechaFin);

        Usuario lider = usuarioRepository.findById(liderId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Líder no encontrado"));

        var contexto = estacionContext.resolverContextoActivoLectura();
        Page<RegistroVueloDiario> registros;

        if (fechaInicio != null && fechaFin != null) {
            registros = registroRepository.findByRegistradoPorAndFechaRegistroBetween(
                    lider, fechaInicio, fechaFin, contexto.estacionId(), contexto.lineaAereaId(), pageable);
        } else {
            registros = registroRepository.findByRegistradoPorAndActivoTrue(
                    lider, contexto.estacionId(), contexto.lineaAereaId(), pageable);
        }

        return registros.map(mapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RegistroVueloDiarioResponse> obtenerTodos(Pageable pageable) {
        log.info("Obteniendo todos los registros activos");

        var contexto = estacionContext.resolverContextoActivoLectura();
        Page<RegistroVueloDiario> registros = registroRepository.findAllByActivoTrue(
                contexto.estacionId(), contexto.lineaAereaId(), pageable);
        return registros.map(mapper::toResponse);
    }

    @Override
    public RegistroVueloDiarioResponse actualizarRecursos(
            Long id,
            RegistroVueloDiarioRequest request,
            Long liderId
    ) {
        log.info("Actualizando recursos del registro {} por líder {}", id, liderId);

        // 1. Buscar el registro
        RegistroVueloDiario registro = getOrThrow(id);

        // 2. Validar que el líder existe
        Usuario lider = usuarioRepository.findById(liderId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Líder no encontrado"));

        // 3. Actualizar observaciones si se proporcionan
        if (request.observaciones() != null) {
            registro.setObservaciones(request.observaciones());
        }

        // 4. Sincronizar recursos (similar a VueloService)
        if (request.recursos() != null) {
            sincronizarRecursos(registro, request.recursos(), lider);
        }

        // 5. Guardar cambios
        registro = registroRepository.save(registro);

        log.info("Registro {} actualizado con {} recursos activos",
                id, registro.getRecursos().stream().filter(r -> r.getEstado() == 1).count());

        return mapper.toResponse(registro);
    }

    @Override
    public void eliminarRegistro(Long id, Long liderId) {
        log.info("Eliminando registro {} por líder {}", id, liderId);

        RegistroVueloDiario registro = getOrThrow(id);

        // Soft-delete: marcar como inactivo
        registro.setActive(false);

        // Desactivar todos los recursos asociados
        registro.getRecursos().forEach(recurso -> recurso.setEstado(0));

        registroRepository.save(registro);
        log.info("Registro {} marcado como inactivo", id);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existeRegistro(Long vueloItinerarioId, LocalDate fecha) {
        return registroRepository.existsByVueloItinerarioIdAndFechaRegistroAndActivoTrue(
                vueloItinerarioId, fecha);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Métodos privados auxiliares
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Habilita recursos para un nuevo registro.
     */
    private void habilitarRecursos(
            RegistroVueloDiario registro,
            List<VueloRecursoRequest> recursosRequest,
            Usuario lider
    ) {
        for (VueloRecursoRequest recursoReq : recursosRequest) {
            Proveedor proveedor = proveedorRepository.findById(recursoReq.proveedorId())
                    .orElseThrow(() -> new RecursoNoEncontradoException(
                            "Proveedor no encontrado: " + recursoReq.proveedorId()));

            VueloRecurso recurso = VueloRecurso.builder()
                    .vuelo(registro.getVueloItinerario())
                    .registroVueloDiario(registro) // ✅ Nueva FK
                    .proveedor(proveedor)
                    .habitacionesSimples(recursoReq.habitacionesSimples())
                    .habitacionesDobles(recursoReq.habitacionesDobles())
                    .habitacionesMatrimoniales(recursoReq.habitacionesMatrimoniales())
                    .capacidadTotal(recursoReq.capacidadTotal())
                    .habilitadoPor(lider)
                    .habilitadoEn(DateTimeUtil.ahoraEnLima())
                    .estado(1)
                    .build();
            // El VueloRecurso hereda estación/línea aérea del registro
            recurso.setEstacionId(registro.getEstacionId());
            recurso.setLineaAereaId(registro.getLineaAereaId());

            registro.agregarRecurso(recurso);
        }
    }

    /**
     * Sincroniza recursos de un registro existente (upsert).
     * - Recursos en request → crear o actualizar
     * - Recursos no en request → desactivar
     */
    private void sincronizarRecursos(
            RegistroVueloDiario registro,
            List<VueloRecursoRequest> recursosRequest,
            Usuario lider
    ) {
        // Obtener IDs de proveedores en el request
        List<Long> proveedoresEnRequest = recursosRequest.stream()
                .map(VueloRecursoRequest::proveedorId)
                .toList();

        // Desactivar recursos que ya NO están en el request
        registro.getRecursos().forEach(recursoExistente -> {
            if (!proveedoresEnRequest.contains(recursoExistente.getProveedor().getId())) {
                recursoExistente.setEstado(0);
                log.debug("Desactivando recurso de proveedor {}",
                        recursoExistente.getProveedor().getNombre());
            }
        });

        // Crear o actualizar recursos del request
        for (VueloRecursoRequest recursoReq : recursosRequest) {
            VueloRecurso recursoExistente = registro.getRecursos().stream()
                    .filter(r -> r.getProveedor().getId().equals(recursoReq.proveedorId()))
                    .findFirst()
                    .orElse(null);

            if (recursoExistente != null) {
                // Actualizar existente
                recursoExistente.setHabitacionesSimples(recursoReq.habitacionesSimples());
                recursoExistente.setHabitacionesDobles(recursoReq.habitacionesDobles());
                recursoExistente.setHabitacionesMatrimoniales(recursoReq.habitacionesMatrimoniales());
                recursoExistente.setCapacidadTotal(recursoReq.capacidadTotal());
                recursoExistente.setEstado(1); // Reactivar si estaba desactivado
                log.debug("Actualizando recurso de proveedor {}",
                        recursoExistente.getProveedor().getNombre());
            } else {
                // Crear nuevo
                Proveedor proveedor = proveedorRepository.findById(recursoReq.proveedorId())
                        .orElseThrow(() -> new RecursoNoEncontradoException(
                                "Proveedor no encontrado: " + recursoReq.proveedorId()));

                VueloRecurso nuevoRecurso = VueloRecurso.builder()
                        .vuelo(registro.getVueloItinerario())
                        .registroVueloDiario(registro)
                        .proveedor(proveedor)
                        .habitacionesSimples(recursoReq.habitacionesSimples())
                        .habitacionesDobles(recursoReq.habitacionesDobles())
                        .habitacionesMatrimoniales(recursoReq.habitacionesMatrimoniales())
                        .capacidadTotal(recursoReq.capacidadTotal())
                        .habilitadoPor(lider)
                        .habilitadoEn(LocalDateTime.now())
                        .estado(1)
                        .build();
                // El VueloRecurso hereda estación/línea aérea del registro
                nuevoRecurso.setEstacionId(registro.getEstacionId());
                nuevoRecurso.setLineaAereaId(registro.getLineaAereaId());

                registro.agregarRecurso(nuevoRecurso);
                log.debug("Creando nuevo recurso de proveedor {}", proveedor.getNombre());
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Capacidad comprometida HOY — Camino B
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Agrega en un Map la capacidad ya comprometida hoy por cada proveedor.
     *
     * Itera todos los VueloRecurso activos del día (una sola consulta),
     * agrupa por proveedorId y suma las cantidades.
     * Si excludeRegistroId != null, omite los recursos de ese registro
     * (para que en edición los recursos propios no se cuenten doble).
     *
     * Complejidad: O(n) donde n = recursos activos del día.
     * En la práctica n es muy pequeño (pocos vuelos × pocos proveedores).
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, CapacidadComprometidaResponse> obtenerCapacidadComprometidaHoy(
            Long excludeRegistroId) {

        LocalDate hoyLima = DateTimeUtil.hoyEnLima();
        LocalDate ayerLima = hoyLima.minusDays(1);
        log.info("[Comprometido] Calculando capacidad comprometida entre {} y {} (excl. registro {})",
                ayerLima, hoyLima, excludeRegistroId);

        List<VueloRecurso> recursosHoy =
                vueloRecursoRepository.findActivosPorRangoFechaExcluyendoRegistro(
                        ayerLima, hoyLima, excludeRegistroId);

        Map<Long, CapacidadComprometidaResponse> mapa = new HashMap<>();

        for (VueloRecurso vr : recursosHoy) {
            Long pid  = vr.getProveedor().getId();
            String nombre = vr.getProveedor().getNombre();
            String tipo   = vr.getProveedor().getTipo().name();

            if (mapa.containsKey(pid)) {
                // Acumular sobre el existente
                CapacidadComprometidaResponse prev = mapa.get(pid);

                if ("HOTEL".equals(tipo)) {
                    mapa.put(pid, new CapacidadComprometidaResponse(
                            pid, nombre, tipo,
                            nvl(prev.simplesComprometidos())      + nvl(vr.getHabitacionesSimples()),
                            nvl(prev.doblesComprometidos())       + nvl(vr.getHabitacionesDobles()),
                            nvl(prev.matrimonialesComprometidos())+ nvl(vr.getHabitacionesMatrimoniales()),
                            null
                    ));
                } else {
                    mapa.put(pid, new CapacidadComprometidaResponse(
                            pid, nombre, tipo,
                            null, null, null,
                            nvl(prev.capacidadTotalComprometida()) + nvl(vr.getCapacidadTotal())
                    ));
                }
            } else {
                // Primera aparición del proveedor
                if ("HOTEL".equals(tipo)) {
                    mapa.put(pid, new CapacidadComprometidaResponse(
                            pid, nombre, tipo,
                            nvl(vr.getHabitacionesSimples()),
                            nvl(vr.getHabitacionesDobles()),
                            nvl(vr.getHabitacionesMatrimoniales()),
                            null
                    ));
                } else {
                    mapa.put(pid, new CapacidadComprometidaResponse(
                            pid, nombre, tipo,
                            null, null, null,
                            nvl(vr.getCapacidadTotal())
                    ));
                }
            }
        }

        log.info("[Comprometido] {} proveedor(es) con capacidad comprometida hoy", mapa.size());
        return mapa;
    }

    private static int nvl(Integer v) { return v != null ? v : 0; }

    /**
     * ANTES: si el usuario era Administrador Global (`propias.isEmpty()`),
     * este método dejaba pasar TODO sin filtrar — por eso el switcher de
     * estación/línea del topbar no tenía ningún efecto visible para ese
     * rol (causa raíz #1 del diagnóstico). AHORA: el contexto de trabajo
     * activo (estación+línea) es obligatorio para cualquier usuario que
     * no tenga un único par fijo asignado — incluido el Administrador
     * Global — y se resuelve desde los headers que manda el topbar
     * (ver ContextoActivoHolder), no desde el JWT.
     */
    private List<RegistroVueloDiario> filtrarPorEstacion(List<RegistroVueloDiario> registros) {
        var contexto = estacionContext.resolverContextoActivoLectura();
        return registros.stream()
                .filter(r -> contexto.estacionId().equals(r.getEstacionId())
                        && (contexto.lineaAereaId() == null
                        || contexto.lineaAereaId().equals(r.getLineaAereaId())))
                .toList();
    }

    private Page<RegistroVueloDiario> filtrarPorEstacion(Page<RegistroVueloDiario> page) {
        List<RegistroVueloDiario> filtrados = filtrarPorEstacion(page.getContent());
        return new PageImpl<>(filtrados, page.getPageable(), filtrados.size());
    }

    /**
     * Obtiene el registro validando que el usuario tenga acceso a su
     * estación+línea — reutilizado en lectura y escritura (obtenerPorId,
     * actualizarRecursos, eliminarRegistro) para que ningún acceso directo
     * por ID quede sin proteger, igual que el patrón usado en VueloServiceImpl.
     */
    private RegistroVueloDiario getOrThrow(Long id) {
        RegistroVueloDiario registro = registroRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Registro no encontrado con ID: " + id));
        estacionContext.validarAccesoLectura(registro);
        return registro;
    }
}