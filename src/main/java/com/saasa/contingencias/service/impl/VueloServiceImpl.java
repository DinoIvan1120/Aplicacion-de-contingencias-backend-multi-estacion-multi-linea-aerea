package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.*;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.dto.request.*;
import com.saasa.contingencias.domain.dto.response.*;
import com.saasa.contingencias.domain.enumeration.*;
import com.saasa.contingencias.domain.mapping.VueloMapper;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.service.IVueloExcelService;
import com.saasa.contingencias.service.IVueloService;
import com.saasa.contingencias.util.DateTimeUtil;
import com.saasa.contingencias.util.IataCodigo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class VueloServiceImpl implements IVueloService {

    private static final Logger log = LoggerFactory.getLogger(VueloServiceImpl.class);

    private final VueloRepository vueloRepository;
    private final VueloRecursoRepository vueloRecursoRepository;
    private final UsuarioRepository usuarioRepository;
    private final ProveedorRepository proveedorRepository;
    private final AtencionRepository atencionRepository;
    private final IVueloExcelService vueloExcelService;
    private final VueloMapper vueloMapper;
    private final EstacionContext estacionContext;
    private final LineaAereaRepository lineaAereaRepository;
    private final EstacionLineaAereaRepository estacionLineaAereaRepository;
    private final EstacionRepository estacionRepository;

    public VueloServiceImpl(VueloRepository vueloRepository,
                            VueloRecursoRepository vueloRecursoRepository,
                            UsuarioRepository usuarioRepository,
                            ProveedorRepository proveedorRepository,
                            AtencionRepository atencionRepository,
                            IVueloExcelService vueloExcelService
            ,VueloMapper vueloMapper,EstacionContext estacionContext,
                            LineaAereaRepository lineaAereaRepository,
                            EstacionLineaAereaRepository estacionLineaAereaRepository,
                            EstacionRepository estacionRepository) {
        this.vueloRepository = vueloRepository;
        this.vueloRecursoRepository = vueloRecursoRepository;
        this.usuarioRepository = usuarioRepository;
        this.proveedorRepository = proveedorRepository;
        this.atencionRepository = atencionRepository;
        this.vueloExcelService = vueloExcelService;
        this.vueloMapper = vueloMapper;
        this.estacionContext = estacionContext;
        this.lineaAereaRepository = lineaAereaRepository;
        this.estacionLineaAereaRepository = estacionLineaAereaRepository;
        this.estacionRepository = estacionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VueloResponse> findAll(Pageable pageable) {
        Specification<Vuelo> filtroContexto = EstacionSpecifications.porContextoDelUsuario(
                estacionContext.resolverContextoActivoLectura());
        return vueloRepository.findAll(filtroContexto, pageable).map(vueloMapper::toResponse);
    }

    /**
     * Búsqueda dinámica con filtros opcionales via Specification.
     * Todos los parámetros son opcionales; los null se ignoran.
     * Igual que /usuarios/buscar pero para vuelos.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<VueloResponse> buscar(String aerolinea, String codigoVuelo, String origen,
                                      String destino, ContingenciaEnum tipoContingencia,
                                      EstadoVueloEnum estado, Pageable pageable) {
        Specification<Vuelo> spec = VueloSpecification.build(
                aerolinea, codigoVuelo, origen, destino, tipoContingencia, estado);
        Specification<Vuelo> filtroContexto = EstacionSpecifications.porContextoDelUsuario(
                estacionContext.resolverContextoActivoLectura());
        if (filtroContexto != null) {
            spec = (spec == null) ? filtroContexto : spec.and(filtroContexto);
        }
        return vueloRepository.findAll(spec, pageable).map(vueloMapper::toResponse);
    }

    @Override
    @Transactional
    public VueloResponse create(VueloRequest request, Long usuarioId) {
        validateIata(request.origen(), request.destino());
        // Validar que no exista el mismo vuelo para la misma fecha
        String codigo = request.codigoVuelo().toUpperCase().trim();
        validateNoFechaPasada(request.fechaVuelo());
        validateNoDuplicado(codigo, request.fechaVuelo(), null);
        Usuario usuario = getUsuario(usuarioId);
        ScopeEstacionLinea contexto = estacionContext.resolverContextoActivo(request.estacionId(), request.lineaAereaId());
        validarAerolineaCoincideConContexto(contexto.lineaAereaId(), request.aerolinea());
        Vuelo v = Vuelo.builder()
                .aerolinea(request.aerolinea())
                .codigoVuelo(request.codigoVuelo().toUpperCase().trim())
                .origen(request.origen())
                .destino(request.destino())
                .fechaVuelo(request.fechaVuelo())
                .tipoContingencia(request.tipoContingencia())
                .observaciones(request.observaciones())
                .estado(EstadoVueloEnum.ACTIVO)
                .creadoPor(usuario)
                .build();
        v.setEstacionId(contexto.estacionId());
        v.setLineaAereaId(contexto.lineaAereaId());
        return vueloMapper.toResponse(vueloRepository.save(v));
    }

    @Override
    @Transactional
    public VueloResponse update(Long id, VueloRequest request) {
        Vuelo v = getOrThrow(id);
        validateIata(request.origen(), request.destino());
        // Validar que no exista el mismo vuelo para la misma fecha
        String codigo = request.codigoVuelo().toUpperCase().trim();

        // Si la fecha cambió respecto a la almacenada, validar que la nueva
        // no sea pasada. Si es la misma fecha (solo edita otros campos) se
        // permite aunque ya sea una fecha anterior a hoy.
        if (!request.fechaVuelo().equals(v.getFechaVuelo()))
            validateNoFechaPasada(request.fechaVuelo());

        validateNoDuplicado(codigo, request.fechaVuelo(), id);

        v.setAerolinea(request.aerolinea());
        v.setCodigoVuelo(request.codigoVuelo().toUpperCase().trim());
        v.setOrigen(request.origen());
        v.setDestino(request.destino());
        v.setFechaVuelo(request.fechaVuelo());
        v.setTipoContingencia(request.tipoContingencia());
        v.setObservaciones(request.observaciones());
        return vueloMapper.toResponse(vueloRepository.save(v));
    }

    @Override
    @Transactional
    public void anular(Long id) {
        Vuelo v = getOrThrow(id);
        if (atencionRepository.existsByVueloId(id)) {
            throw new BadRequestException(
                    "No se puede anular el vuelo porque tiene atenciones asociadas");
        }
        v.setEstado(EstadoVueloEnum.ANULADO);
        vueloRepository.save(v);
    }

    /**
     * Reactiva un vuelo previamente ANULADO → lo vuelve a ACTIVO.
     * Solo aplica si el vuelo está en estado ANULADO; si ya está ACTIVO
     * se lanza BadRequestException para evitar operaciones redundantes.
     */
    @Override
    @Transactional
    public void habilitar(Long id) {
        Vuelo v = getOrThrow(id);
        if (v.getEstado() == EstadoVueloEnum.ACTIVO) {
            throw new BadRequestException("El vuelo ya se encuentra ACTIVO");
        }
        v.setEstado(EstadoVueloEnum.ACTIVO);
        vueloRepository.save(v);
    }

    @Override
    public List<VueloRecursoResponse> findRecursos(Long vueloId) {
        return vueloRecursoRepository.findByVueloIdAndEstado(vueloId, 1)
                .stream().map(vueloMapper::toRecursoResponse).toList();
    }

    @Override
    @Transactional
    public VueloRecursoResponse habilitarRecurso(Long vueloId,
                                                 VueloRecursoRequest request, Long usuarioId) {
        Vuelo vuelo = getOrThrow(vueloId);
        Proveedor proveedor = proveedorRepository.findById(request.proveedorId())
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "Proveedor no encontrado: " + request.proveedorId()));
        if (proveedor.getEstado() == 0) {
            throw new ProveedorInactivoException(
                    "El proveedor '" + proveedor.getNombre() + "' está inactivo");
        }
        if (vueloRecursoRepository.existsByVueloIdAndProveedorId(vueloId, proveedor.getId())) {
            throw new BadRequestException(
                    "El proveedor '" + proveedor.getNombre() + "' ya está habilitado para este vuelo");
        }
        // Validar que para HOTEL se especifiquen habitaciones
        String tipoProveedor = proveedor.getTipo().name();
        if ("HOTEL".equals(tipoProveedor)) {
            int total = (request.habitacionesSimples()       != null ? request.habitacionesSimples()       : 0)
                    + (request.habitacionesDobles()        != null ? request.habitacionesDobles()        : 0)
                    + (request.habitacionesMatrimoniales() != null ? request.habitacionesMatrimoniales() : 0);
            if (total == 0) {
                throw new BadRequestException(
                        "Debe especificar al menos un tipo de habitación (simples, dobles o matrimoniales)");
            }
        }

        VueloRecurso vr = VueloRecurso.builder()
                .vuelo(vuelo)
                .proveedor(proveedor)
                .habitacionesSimples(request.habitacionesSimples())
                .habitacionesDobles(request.habitacionesDobles())
                .habitacionesMatrimoniales(request.habitacionesMatrimoniales())
                .capacidadTotal(request.capacidadTotal())
                .habilitadoPor(getUsuario(usuarioId))
                .habilitadoEn(DateTimeUtil.ahoraEnLima())
                .estado(1)
                .build();
        vr.setEstacionId(vuelo.getEstacionId());
        vr.setLineaAereaId(vuelo.getLineaAereaId());
        return vueloMapper.toRecursoResponse(vueloRecursoRepository.save(vr));
    }

    @Override
    @Transactional
    public void deshabilitarRecurso(Long vueloId, Long recursoId) {
        VueloRecurso vr = vueloRecursoRepository.findById(recursoId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "Recurso no encontrado: " + recursoId));
        vr.setEstado(0);
        vueloRecursoRepository.save(vr);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Itinerario del día — Vista Líder
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Devuelve los vuelos ACTIVOS cuya fechaVuelo = hoy en Lima (UTC-5).
     *
     * Por qué fechaVuelo y no fechaRegistro:
     *   - fechaVuelo es el día del vuelo real (ej. 30/05).
     *   - El admin registra vuelos con la fecha del día que operarán.
     *   - El líder ve solo los vuelos programados para HOY.
     *   - Al día siguiente el líder ve automáticamente los vuelos con
     *     fechaVuelo de ese nuevo día, sin ninguna acción manual.
     *
     * Usa DateTimeUtil.hoyEnLima() para consistencia UTC-5 en todo el sistema.
     */
    @Override
    @Transactional(readOnly = true)
    public List<VueloResponse> obtenerItinerarioHoy() {
        LocalDate hoyLima = DateTimeUtil.hoyEnLima();
        log.info("[Itinerario] Obteniendo vuelos del día {} (Lima UTC-5)", hoyLima);

        Specification<Vuelo> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("fechaVuelo"), hoyLima),
                cb.equal(root.get("estado"), EstadoVueloEnum.ACTIVO)
        );
        Specification<Vuelo> filtroContexto = EstacionSpecifications.porContextoDelUsuario(
                estacionContext.resolverContextoActivoLectura());
        if (filtroContexto != null) {
            spec = spec.and(filtroContexto);
        }

        List<Vuelo> vuelosHoy = vueloRepository.findAll(spec,
                Sort.by(Sort.Direction.ASC, "codigoVuelo"));
        log.info("[Itinerario] {} vuelo(s) activos para hoy", vuelosHoy.size());
        return vuelosHoy.stream().map(vueloMapper::toResponse).toList();
    }

    // ─── MEJORA 4: Carga masiva con normalización robusta de texto ────────────────────
    @Override
    @Transactional
    public CargaMasivaResponse cargarDesdeExcel(MultipartFile archivo, Long usuarioId, Long estacionId, Long lineaAereaId) {
        return vueloExcelService.cargarDesdeExcel(archivo, usuarioId, estacionId, lineaAereaId);
    }

    // ─── Validaciones y mapeos ────────────────────────────────────────────────────────

    /**
     * Lanza {@link BadRequestException} si la fecha del vuelo es anterior a hoy en Lima.
     * Solo aplica en create() y crearRegistroCompleto(). En update() no aplica
     * porque el vuelo ya existe y puede conservar su fecha original.
     * Usa DateTimeUtil.hoyEnLima() para comparar contra la fecha real de Lima (UTC-5).
     */
    private void validateNoFechaPasada(LocalDate fechaVuelo) {
        LocalDate hoyLima = DateTimeUtil.hoyEnLima();
        if (fechaVuelo != null && fechaVuelo.isBefore(hoyLima))
            throw new BadRequestException(
                    "La fecha del vuelo " + fechaVuelo +
                            " es anterior a hoy (" + hoyLima + " hora Lima). " +
                            "No se puede registrar un vuelo con fecha pasada.");
    }

    /**
     * Lanza BadRequest si ya existe un vuelo con el mismo
     * código y fecha. Cuando {@code excludeId} no es null (modo update) se
     * excluye ese registro para no generar un falso positivo sobre sí mismo.
     */
    private void validateNoDuplicado(String codigoVuelo, java.time.LocalDate fechaVuelo, Long excludeId) {
        boolean duplicado = (excludeId == null)
                ? vueloRepository.existsByCodigoVueloAndFechaVuelo(codigoVuelo, fechaVuelo)
                : vueloRepository.existsByCodigoVueloAndFechaVueloAndIdNot(codigoVuelo, fechaVuelo, excludeId);
        if (duplicado) {
            throw new BadRequestException(
                    "Ya existe el vuelo " + codigoVuelo + " para la fecha " + fechaVuelo +
                            ". Un vuelo es único por número y día.");
        }
    }

    private void validateIata(String origen, String destino) {
        if (!IataCodigo.isValid(origen))
            throw new BadRequestException("Código IATA origen inválido: '" + origen + "'");
        if (!IataCodigo.isValid(destino))
            throw new BadRequestException("Código IATA destino inválido: '" + destino + "'");
    }

    private Vuelo getOrThrow(Long id) {
        Vuelo v = vueloRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Vuelo no encontrado: " + id));
        estacionContext.validarAccesoLectura(v);
        return v;
    }

    private Usuario getUsuario(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado: " + id));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VISTA UNIFICADA DEL LÍDER
    // Endpoints: POST /registro · GET /{id}/registro · PUT /{id}/registro
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * CREAR — Primera vez que el Líder presiona "Guardar Información de Vuelo".
     * Crea el vuelo y habilita todos los recursos seleccionados en una transacción.
     */
    @Override
    @Transactional
    public RegistroVueloResponse crearRegistroCompleto(RegistroVueloRequest request, Long usuarioId) {
        validateIata(request.origen(), request.destino());
        // Validar duplicado también en el flujo unificado del Líder
        String codigo = request.codigoVuelo().toUpperCase().trim();
        validateNoFechaPasada(request.fechaVuelo());
        validateNoDuplicado(codigo, request.fechaVuelo(), null);
        Usuario usuario = getUsuario(usuarioId);
        ScopeEstacionLinea contexto = estacionContext.resolverContextoActivo(request.estacionId(), request.lineaAereaId());
        validarAerolineaCoincideConContexto(contexto.lineaAereaId(), request.aerolinea());
        Vuelo v = Vuelo.builder()
                .aerolinea(request.aerolinea())
                .codigoVuelo(request.codigoVuelo().toUpperCase().trim())
                .origen(request.origen())
                .destino(request.destino())
                .fechaVuelo(request.fechaVuelo())
                .tipoContingencia(request.tipoContingencia())
                .observaciones(request.observaciones())
                .estado(EstadoVueloEnum.ACTIVO)
                .creadoPor(usuario)
                .build();
        v.setEstacionId(contexto.estacionId());
        v.setLineaAereaId(contexto.lineaAereaId());
        v = vueloRepository.save(v);

        List<VueloRecurso> recursos = new ArrayList<>();
        if (request.recursos() != null && !request.recursos().isEmpty()) {
            recursos = habilitarRecursosDesdeList(v, request.recursos(), usuarioId);
        }
        return vueloMapper.toRegistroResponse(v, recursos);
    }

    /**
     * OBTENER — Carga la vista completa del Líder con una sola llamada.
     * Devuelve el vuelo + todos sus recursos activos (hoteles, transportes, restaurantes).
     * Usado también para mostrar "Resumen de Recursos Habilitados" (imagen 9).
     */
    @Override
    public RegistroVueloResponse obtenerRegistroCompleto(Long vueloId) {
        Vuelo v = getOrThrow(vueloId);
        List<VueloRecurso> recursos = vueloRecursoRepository.findByVueloIdAndEstado(vueloId, 1);
        return vueloMapper.toRegistroResponse(v, recursos);
    }

    /**
     * ACTUALIZAR — El Líder presiona "Guardar Información de Vuelo" cuando ya existe el registro.
     *
     * Sincronización de recursos:
     *   - Proveedores EN el request   → upsert (crea o actualiza)
     *   - Proveedores NO en el request → se desactivan (estado=0)
     * Esto garantiza que la BD queda exactamente igual a lo que el Líder guardó.
     */
    @Override
    @Transactional
    public RegistroVueloResponse actualizarRegistroCompleto(Long vueloId,
                                                            RegistroVueloRequest request, Long usuarioId) {

        Vuelo v = getOrThrow(vueloId);
        validateIata(request.origen(), request.destino());

        // Al actualizar excluimos el propio vuelo para no generar falso positivo
        String codigo = request.codigoVuelo().toUpperCase().trim();
        validateNoDuplicado(codigo, request.fechaVuelo(), vueloId);

        v.setAerolinea(request.aerolinea());
        v.setCodigoVuelo(request.codigoVuelo().toUpperCase().trim());
        v.setOrigen(request.origen());
        v.setDestino(request.destino());
        v.setFechaVuelo(request.fechaVuelo());
        v.setTipoContingencia(request.tipoContingencia());
        v.setObservaciones(request.observaciones());
        v = vueloRepository.save(v);

        List<VueloRecurso> activos = sincronizarRecursos(v, request.recursos(), usuarioId);
        return vueloMapper.toRegistroResponse(v, activos);
    }

    // ─── Sincroniza la lista completa de recursos del Líder ───────────────────
    private List<VueloRecurso> sincronizarRecursos(Vuelo vuelo,
                                                   List<VueloRecursoRequest> nuevos, Long usuarioId) {

        // IDs de proveedores que el Líder incluyó en este guardado
        java.util.Set<Long> idsEnRequest = new java.util.HashSet<>();
        if (nuevos != null) {
            nuevos.forEach(r -> idsEnRequest.add(r.proveedorId()));
        }

        // Desactivar los recursos que ya NO están en la lista
        vueloRecursoRepository.findByVueloIdAndEstado(vuelo.getId(), 1).forEach(vr -> {
            if (!idsEnRequest.contains(vr.getProveedor().getId())) {
                vr.setEstado(0);
                vueloRecursoRepository.save(vr);
            }
        });

        if (nuevos == null || nuevos.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return habilitarRecursosDesdeList(vuelo, nuevos, usuarioId);
    }

    // ─── Crea o actualiza (upsert) cada recurso de la lista ───────────────────
    private List<VueloRecurso> habilitarRecursosDesdeList(Vuelo vuelo,
                                                          List<VueloRecursoRequest> recursos, Long usuarioId) {

        List<VueloRecurso> resultado = new ArrayList<>();
        for (VueloRecursoRequest rec : recursos) {
            Proveedor proveedor = proveedorRepository.findById(rec.proveedorId())
                    .orElseThrow(() -> new RecursoNoEncontradoException(
                            "Proveedor no encontrado: " + rec.proveedorId()));

            if (proveedor.getEstado() == 0)
                throw new ProveedorInactivoException(
                        "El proveedor '" + proveedor.getNombre() + "' está inactivo");

            // Validar hotel: al menos 1 habitación > 0
            if ("HOTEL".equals(proveedor.getTipo().name())) {
                int total = (rec.habitacionesSimples()       != null ? rec.habitacionesSimples()       : 0)
                        + (rec.habitacionesDobles()        != null ? rec.habitacionesDobles()        : 0)
                        + (rec.habitacionesMatrimoniales() != null ? rec.habitacionesMatrimoniales() : 0);
                if (total == 0)
                    throw new BadRequestException(
                            "Hotel '" + proveedor.getNombre() + "': especifique al menos un tipo de habitación > 0");
            }

            // Buscar existente (activo o inactivo) para hacer upsert
            var existente = vueloRecursoRepository
                    .findByVueloIdAndProveedorIdOptional(vuelo.getId(), proveedor.getId());

            VueloRecurso vr;
            if (existente.isPresent()) {
                vr = existente.get();
                vr.setHabitacionesSimples(rec.habitacionesSimples());
                vr.setHabitacionesDobles(rec.habitacionesDobles());
                vr.setHabitacionesMatrimoniales(rec.habitacionesMatrimoniales());
                vr.setCapacidadTotal(rec.capacidadTotal());
                vr.setEstado(1);
                vr.setHabilitadoPor(getUsuario(usuarioId));
                vr.setHabilitadoEn(DateTimeUtil.ahoraEnLima());
            } else {
                vr = VueloRecurso.builder()
                        .vuelo(vuelo).proveedor(proveedor)
                        .habitacionesSimples(rec.habitacionesSimples())
                        .habitacionesDobles(rec.habitacionesDobles())
                        .habitacionesMatrimoniales(rec.habitacionesMatrimoniales())
                        .capacidadTotal(rec.capacidadTotal())
                        .habilitadoPor(getUsuario(usuarioId))
                        .habilitadoEn(DateTimeUtil.ahoraEnLima())
                        .estado(1)
                        .build();
            }
            vr.setEstacionId(vuelo.getEstacionId());
            vr.setLineaAereaId(vuelo.getLineaAereaId());
            resultado.add(vueloRecursoRepository.save(vr));
        }
        return resultado;
    }

    /**
     * Antes, la línea aérea del vuelo se resolvía/creaba automáticamente a
     * partir del texto libre `aerolinea`, sin relación con el usuario que
     * operaba. Ahora que la línea aérea es también eje de aislamiento, el
     * `lineaAereaId` real del registro lo determina SIEMPRE el contexto de
     * trabajo activo del usuario (topbar), nunca el texto libre — este
     * método solo valida que ambos coincidan, para detectar errores de
     * tipeo o de selector desincronizado.
     */
    private void validarAerolineaCoincideConContexto(Long lineaAereaIdContexto, String aerolineaTexto) {
        String nombre = aerolineaTexto == null ? "" : aerolineaTexto.trim();
        if (nombre.isEmpty()) throw new BadRequestException("La aerolínea es obligatoria");
        LineaAerea lineaDelContexto = lineaAereaRepository.findById(lineaAereaIdContexto)
                .orElseThrow(() -> new RecursoNoEncontradoException("Línea aérea no encontrada: " + lineaAereaIdContexto));
        if (!lineaDelContexto.getNombre().equalsIgnoreCase(nombre)) {
            throw new BadRequestException(
                    "La aerolínea '" + nombre + "' no coincide con el contexto de trabajo activo ('"
                            + lineaDelContexto.getNombre() + "'). Verifique el selector de estación/línea del topbar.");
        }
    }

    // Usado en VueloExcelServiceImpl
    private String generarCodigoIataProvisional(String nombreAerolinea) {
        String base = nombreAerolinea.trim().toUpperCase().replaceAll("[^A-Z]", "");
        String prefijo = (base.length() >= 3 ? base.substring(0, 3) : (base + "XXX").substring(0, 3));
        String candidato = prefijo;
        int sufijo = 1;
        while (lineaAereaRepository.existsByCodigoIata(candidato)) {
            candidato = prefijo.substring(0, 2) + sufijo;
            sufijo++;
        }
        return candidato;
    }

}