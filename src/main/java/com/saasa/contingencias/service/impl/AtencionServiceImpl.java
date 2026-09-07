package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.*;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.domain.dto.request.*;
import com.saasa.contingencias.domain.dto.response.*;
import com.saasa.contingencias.domain.enumeration.*;
import com.saasa.contingencias.domain.mapping.AtencionMapper;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.service.*;
import com.saasa.contingencias.util.DateTimeUtil;
import com.saasa.contingencias.util.PnrValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class AtencionServiceImpl implements IAtencionService {

    private static final Logger log = LoggerFactory.getLogger(AtencionServiceImpl.class);

    private final AtencionRepository atencionRepository;
    private final ServicioAsignadoRepository servicioAsignadoRepository;
    private final VueloRepository vueloRepository;
    private final VueloRecursoRepository vueloRecursoRepository;
    private final UsuarioRepository usuarioRepository;
    private final ProveedorRepository proveedorRepository;
    private final IAuditoriaService auditoriaService;
    private final SimpMessagingTemplate messagingTemplate;
    private final IDisponibilidadService disponibilidadService;
    private final RegistroVueloDiarioRepository registroVueloDiarioRepository;
    private final ICorrelativoService correlativoService;
    private final IServicioMontoService servicioMontoService;
    private final AtencionMapper atencionMapper;
    private final EstacionContext estacionContext;

    // ── MEJORA 1: ya no se usa AtomicLong. El correlativo se obtiene de la BD ──────────

    public AtencionServiceImpl(AtencionRepository atencionRepository,
                               ServicioAsignadoRepository servicioAsignadoRepository,
                               VueloRepository vueloRepository, VueloRecursoRepository vueloRecursoRepository,
                               UsuarioRepository usuarioRepository, ProveedorRepository proveedorRepository,
                               IAuditoriaService auditoriaService, SimpMessagingTemplate messagingTemplate,
                               IDisponibilidadService disponibilidadService,
                               RegistroVueloDiarioRepository registroVueloDiarioRepository,
                               ICorrelativoService correlativoService,
                               IServicioMontoService servicioMontoService,AtencionMapper atencionMapper,
                               EstacionContext estacionContext
    ) {
        this.atencionRepository = atencionRepository;
        this.servicioAsignadoRepository = servicioAsignadoRepository;
        this.vueloRepository = vueloRepository;
        this.vueloRecursoRepository = vueloRecursoRepository;
        this.usuarioRepository = usuarioRepository;
        this.proveedorRepository = proveedorRepository;
        this.auditoriaService = auditoriaService;
        this.messagingTemplate = messagingTemplate;
        this.disponibilidadService = disponibilidadService;
        this.registroVueloDiarioRepository = registroVueloDiarioRepository;
        this.correlativoService = correlativoService;
        this.servicioMontoService = servicioMontoService;
        this.atencionMapper = atencionMapper;
        this.estacionContext = estacionContext;
    }

    // ─── findAll con filtros por rol (MEJORAS 2 & 3) ─────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public Page<AtencionResponse> findAll(Pageable pageable, String rolUsuario,
                                          Long usuarioId, Long proveedorId) {
        /*
         * MEJORA 2 — LINEA_AEREA: filtra por aerolínea del usuario.
         *   El campo 'documento' del usuario LINEA_AEREA almacena el nombre de su aerolínea.
         *
         * MEJORA 3 — PROVEEDOR: filtra por las atenciones que contienen sus servicios.
         *   Se resuelve el proveedorId desde el correo del usuario en BD.
         */
        String aerolineaUsuario = null;
        Long proveedorIdFiltro = proveedorId;

        if ("LINEA_AEREA".equals(rolUsuario) && usuarioId != null) {
            aerolineaUsuario = usuarioRepository.findDocumentoById(usuarioId).orElse(null);
            if (aerolineaUsuario == null) {
                log.warn("Usuario LINEA_AEREA id={} no tiene aerolínea configurada en campo 'documento'", usuarioId);
            }
        }

        if ("PROVEEDOR".equals(rolUsuario) && usuarioId != null && proveedorIdFiltro == null) {
            // Buscar proveedor por correo del usuario autenticado
            String correoUsuario = usuarioRepository.findById(usuarioId)
                    .map(Usuario::getCorreo).orElse(null);
            if (correoUsuario != null) {
                proveedorIdFiltro = proveedorRepository.findActivoByCorreo(correoUsuario)
                        .map(Proveedor::getId).orElse(null);
            }
        }

        var spec = AtencionSpecification.build(null, rolUsuario, aerolineaUsuario, proveedorIdFiltro);
        var filtroContexto = com.saasa.contingencias.domain.repository.EstacionSpecifications
                .<Atencion>porContextoDelUsuario(estacionContext.resolverContextoActivoLectura());
        if (filtroContexto != null) {
            spec = (spec == null) ? filtroContexto : spec.and(filtroContexto);
        }
        return atencionRepository.findAll(spec, pageable).map(atencionMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AtencionResponse findById(Long id) {
        return atencionMapper.toResponse(getOrThrow(id));
    }

    @Override
    @Transactional
    public AtencionResponse create(AtencionRequest request, Long usuarioId) {
        validateAtencion(request);
        Vuelo vuelo = getVuelo(request.vueloId());

        // Validar y obtener el registro diario
        com.saasa.contingencias.domain.model.RegistroVueloDiario registroDiario =
                registroVueloDiarioRepository.findById(request.registroVueloDiarioId())
                        .orElseThrow(() -> new RecursoNoEncontradoException(
                                "Registro diario no encontrado: " + request.registroVueloDiarioId()));

        // Validar que el registro diario corresponda al vuelo
        if (!registroDiario.getVueloItinerario().getId().equals(request.vueloId())) {
            throw new BadRequestException(
                    "El registro diario no corresponde al vuelo seleccionado");
        }

        // Validar que el registro diario esté activo
        if (registroDiario.getActive() == null || !registroDiario.getActive()) {
            throw new BadRequestException("El registro diario no está activo");
        }

        /*
        if (atencionRepository.existsByPnrAndVueloIdAndEstado(
                request.pnr(), request.vueloId(), EstadoAtencionEnum.ACTIVO)) {
            throw new PnrDuplicadoException(
                    "PNR " + request.pnr() + " ya tiene un voucher activo para este vuelo");
        }

         */
        Usuario agente = getUsuario(usuarioId);

        // ── MEJORA 1: Correlativo desde BD, no desde AtomicLong en memoria ────────────
        String correlativo = correlativoService.generarCorrelativo(vuelo.getEstacionId(), vuelo.getLineaAereaId());

        Atencion a = Atencion.builder()
                .numeroCorrelativo(correlativo)
                .vuelo(vuelo)
                .registroVueloDiario(registroDiario)  // ✅ NUEVO: Asociar con registro diario
                .nombre(request.nombre())
                .apellido(request.apellido())
                .pnr(request.pnr())
                .correo(request.correo())
                .telefono(request.telefono())  // ← TWILIO:
                .codigoBarras(request.codigoBarras())  // ✅ NUEVO: Datos del boarding pass
                .fechaEmision(request.fechaEmision())
                .lugarEmision(request.lugarEmision())
                .grupoId(request.grupoId()) // Voucher grupal
                .montoTotal(BigDecimal.ZERO)
                .estado(EstadoAtencionEnum.ACTIVO)
                .atendidoPor(agente)
                // NUEVO: conformidad / firma digital del pasajero (modal de confirmación)
                .firmaPasajero(request.firmaPasajero())
                .firmaConforme(request.firmaPasajero() != null && !request.firmaPasajero().isBlank())
                .firmaFecha(request.firmaPasajero() != null && !request.firmaPasajero().isBlank()
                        ? com.saasa.contingencias.util.DateTimeUtil.ahoraEnLima() : null)
                // NUEVO — este flujo (creación individual) siempre es firma del propio pasajero
                .origenFirma(request.firmaPasajero() != null && !request.firmaPasajero().isBlank()
                        ? com.saasa.contingencias.domain.enumeration.OrigenFirmaEnum.PASAJERO : null)
                .build();
        // La Atención hereda estación/línea aérea del Vuelo asociado
        a.setEstacionId(vuelo.getEstacionId());
        a.setLineaAereaId(vuelo.getLineaAereaId());

        Atencion saved = atencionRepository.save(a);

// ✅ ACTUALIZADO: Usar método con 7 parámetros
        auditoriaService.registrar(
                usuarioId,
                "CREAR_ATENCION",
                "ATENCIONES",
                buildDetalleJson(correlativo, request.pnr(), request.registroVueloDiarioId()),
                "Atencion",                  // ✅ Tipo de entidad
                saved.getId(),                // ✅ ID de la entidad
                correlativo                   // ✅ Nombre/correlativo
        );

        notificarWebSocket("NUEVA_ATENCION", saved);

        log.info("Atención creada y asociada a registro diario ID: {}", request.registroVueloDiarioId());

        return atencionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public List<AtencionResponse> createBatch(AtencionBatchRequest request, Long usuarioId) {
        List<AtencionResponse> result = new ArrayList<>();
        for (AtencionRequest ar : request.pasajeros()) {
            result.add(create(ar, usuarioId));
        }
        return result;
    }

    @Override
    @Transactional
    public AtencionResponse update(Long id, AtencionRequest request) {
        Atencion a = getOrThrow(id);
        a.setNombre(request.nombre());
        a.setApellido(request.apellido());
        a.setCorreo(request.correo());
        return atencionMapper.toResponse(atencionRepository.save(a));
    }

    @Override
    @Transactional
    public void anular(Long id, Long usuarioId) {
        Atencion a = getOrThrow(id);
        validarPermisoAnulacion(a, usuarioId, "anular");
        validarNoEsAcompanianteDeGrupo(a, "anular");
        if (a.getEstado() == EstadoAtencionEnum.ANULADO) {
            throw new BadRequestException("La atención ya se encuentra anulada.");
        }
        a.setEstado(EstadoAtencionEnum.ANULADO);
        atencionRepository.save(a);
        auditoriaService.registrar(
                usuarioId, "ANULAR_ATENCION", "ATENCIONES",
                String.format("{\"atencionId\":%d,\"motivo\":\"Anulado por usuario\"}", id),
                "Atencion", id, a.getNumeroCorrelativo());
        notificarWebSocket("ATENCION_ANULADA", a);

        // NUEVO: si esta atención es TITULAR de un voucher grupal, anular
        // también a los demás pasajeros del grupo — comparten el mismo
        // servicio/voucher, así que si se anula para uno, se anula para todos.
        propagarEstadoAlGrupo(a, usuarioId, EstadoAtencionEnum.ANULADO, "ANULAR_ATENCION");
    }

    @Override
    @Transactional
    public void restaurar(Long id, Long usuarioId) {
        Atencion a = getOrThrow(id);
        validarPermisoAnulacion(a, usuarioId,"restaurar");
        validarNoEsAcompanianteDeGrupo(a, "restaurar");
        if (a.getEstado() == EstadoAtencionEnum.ACTIVO) {
            throw new BadRequestException("La atención ya se encuentra activa.");
        }
        a.setEstado(EstadoAtencionEnum.ACTIVO);
        atencionRepository.save(a);
        auditoriaService.registrar(
                usuarioId, "RESTAURAR_ATENCION", "ATENCIONES",
                String.format("{\"atencionId\":%d,\"motivo\":\"Restaurado por usuario\"}", id),
                "Atencion", id, a.getNumeroCorrelativo());
        notificarWebSocket("ATENCION_RESTAURADA", a);

        // NUEVO: mismo criterio que anular() — se propaga al resto del grupo.
        propagarEstadoAlGrupo(a, usuarioId, EstadoAtencionEnum.ACTIVO, "RESTAURAR_ATENCION");
    }


    @Override
    @Transactional
    public List<ServicioAsignadoResponse> asignarServicios(Long atencionId,
                                                           List<ServicioAsignadoRequest> servicios, Long usuarioId) {
        Atencion atencion = getOrThrow(atencionId);

        // Obtener registro diario asociado (necesario para validar recursos)
        if (atencion.getRegistroVueloDiario() == null) {
            throw new BadRequestException(
                    "La atención no está asociada a un registro diario");
        }

        Long registroDiarioId = atencion.getRegistroVueloDiario().getId();

        List<ServicioAsignado> asignados = new ArrayList<>();
        BigDecimal totalAcumulado = BigDecimal.ZERO;

        // Se ordena por vueloRecursoId antes de bloquear: si dos agentes piden
        // los mismos recursos combinados en distinto orden, adquirir siempre
        // el lock en el mismo orden evita deadlocks entre ambas transacciones.
        List<ServicioAsignadoRequest> serviciosOrdenados = servicios.stream()
                .sorted(java.util.Comparator.comparing(ServicioAsignadoRequest::vueloRecursoId))
                .toList();

        for (ServicioAsignadoRequest req : serviciosOrdenados) {
            // Obtener el recurso habilitado — CON BLOQUEO PESIMISTA: mientras
            // esta transacción no termine (commit o rollback), ninguna otra
            // transacción puede leer/validar disponibilidad sobre este mismo
            // VueloRecurso. Así se evita que dos agentes atendiendo al mismo
            // tiempo pasen la validación de disponibilidad sobre el mismo
            // cupo ya agotado (sobreventa por condición de carrera).
            com.saasa.contingencias.domain.model.VueloRecurso vueloRecurso =
                    vueloRecursoRepository.findByIdForUpdate(req.vueloRecursoId())
                            .orElseThrow(() -> new RecursoNoEncontradoException(
                                    "Recurso no encontrado: " + req.vueloRecursoId()));

            // Validar que el recurso pertenece al registro diario correcto
            if (!vueloRecurso.getRegistroVueloDiario().getId().equals(registroDiarioId)) {
                throw new BadRequestException(
                        "El recurso no pertenece al registro diario de esta atención");
            }

            // Validar que el recurso está activo
            if (vueloRecurso.getEstado() == null || vueloRecurso.getEstado() != 1) {
                throw new BadRequestException(
                        "El recurso no está activo: " + vueloRecurso.getProveedor().getNombre());
            }

            // VALIDAR DISPONIBILIDAD antes de asignar
            if (req.tipoDetalle() == TipoDetalleEnum.HOTEL) {
                disponibilidadService.validarDisponibilidadHotel(vueloRecurso, req.tipoHabitacion(), req.cantidad());
            } else {
                disponibilidadService.validarDisponibilidadGeneral(vueloRecurso, req.cantidad());
            }

            // ═══════════════════════════════════════════════════════════════════
            // ✅ FIX: CALCULAR MONTO DESDE PROVEEDOR/SERVICIO
            // ═══════════════════════════════════════════════════════════════════
            BigDecimal montoUnitario = servicioMontoService.calcularMonto(vueloRecurso, req);
            //BigDecimal montoUnitario = obtenerMontoServicio(vueloRecurso, req);
            //BigDecimal subtotal = montoUnitario.multiply(BigDecimal.valueOf(req.cantidad()));

            BigDecimal subtotal = (req.tipoDetalle() == TipoDetalleEnum.TRANSPORTE)
                    ? montoUnitario                                          // TRANSPORTE: precio fijo, no se multiplica
                    : montoUnitario.multiply(BigDecimal.valueOf(req.cantidad())); // HOTEL/RESTAURANTE: sí escala

            // Crear servicio asignado con TODOS los campos
            ServicioAsignado sa = ServicioAsignado.builder()
                    .atencion(atencion)
                    .vueloRecurso(vueloRecurso)  // ✅ NUEVO: Usar VueloRecurso
                    .tipoDetalle(req.tipoDetalle())
                    .cantidad(req.cantidad())
                    .tipoHabitacion(req.tipoHabitacion())  // ✅ NUEVO
                    .tipoTransporte(req.tipoTransporte())  // ✅ NUEVO (si existe en req)
                    .desayuno(Boolean.TRUE.equals(req.desayuno()))             // ✅ NUEVO
                    .almuerzo(Boolean.TRUE.equals(req.almuerzo()))               // ✅ NUEVO
                    .cena(Boolean.TRUE.equals(req.cena()))                       // ✅ NUEVO
                    .snack(Boolean.TRUE.equals(req.snack()))
                    .fechaIngreso(req.fechaIngreso())   // ← AGREGAR
                    .fechaSalida(req.fechaSalida())     // ← AGREGAR// ✅ NUEVO
                    .montoUnitario(montoUnitario)  // ✅ Ya no es CERO
                    .montoSubtotal(subtotal)       // ✅ Ya no es CERO
                    .asignadoEn(DateTimeUtil.ahoraEnLima())
                    .build();
            // El ServicioAsignado hereda estación/línea aérea de la Atención
            sa.setEstacionId(atencion.getEstacionId());
            sa.setLineaAereaId(atencion.getLineaAereaId());

            asignados.add(servicioAsignadoRepository.save(sa));
            totalAcumulado = totalAcumulado.add(subtotal);
        }

        atencion.setMontoTotal(atencion.getMontoTotal().add(totalAcumulado));
        atencion.setCodigoAutorizacion(
                atencion.getNumeroCorrelativo() + "-" + System.currentTimeMillis());
        atencionRepository.save(atencion);

        log.info("✅ Servicios asignados a atención {}: total S/ {}",
                atencionId, totalAcumulado);

        //Nuevo: Notificar cambio de disponibilidad via WebSocket tras asignar servicios

        try{
            disponibilidadService.notificarCambioDisponibilidad(registroDiarioId);
            log.info("Disponibilidad notificada por WebSocket para registro diario: {}", registroDiarioId);
        }catch (Exception e){
            log.warn("No se pudo notificar disponibilidad: {}", e.getMessage());
        }

        return asignados.stream()
                .map(atencionMapper::toServicioResponse)
                .toList();
    }

    // ── Validaciones ──────────────────────────────────────────────────────────────────
    private void validateAtencion(AtencionRequest req) {
        if (!PnrValidator.isValid(req.pnr())) {
            throw new BadRequestException(
                    "PNR inválido: debe tener 6 caracteres alfanuméricos en mayúsculas");
        }
        if (req.correo() == null || req.correo().isBlank()) {
            throw new BadRequestException("El correo electrónico es obligatorio");
        }
    }

    /**
     * Regla de permiso para anular/restaurar
     * - ADMINISTRADOR y LIDER SAASA: pueden operar sobre cualquier atención.
     * - AGENTE SAASA: solo puede operar sobre las atenciones que él mismo genero.
     */
    private void validarPermisoAnulacion(Atencion atencion, Long usuarioId, String accion){
        Usuario usuario = getUsuario(usuarioId);
        if(usuario.getRol() == RolEnum.AGENTE_SAASA){
            Long propietarioId = atencion.getAtendidoPor() != null ? atencion.getAtendidoPor().getId(): null;
            if(propietarioId == null || !propietarioId.equals(usuarioId)){
                throw new AccesoDenegadoException("No puedes " + accion + " este reporte porque no lo generaste tú.");
            }
        }
    }

    private Vuelo getVuelo(Long id) {
        Vuelo v = vueloRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Vuelo no encontrado: " + id));
        if (v.getEstado() == EstadoVueloEnum.ANULADO) {
            throw new BadRequestException("No se puede crear atención para un vuelo anulado");
        }
        return v;
    }

    private Atencion getOrThrow(Long id) {
        Atencion a = atencionRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Atención no encontrada: " + id));
        estacionContext.validarAccesoLectura(a);
        return a;
    }

    private Usuario getUsuario(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado: " + id));
    }

    private void notificarWebSocket(String tipo, Atencion a) {
        try {
            messagingTemplate.convertAndSend("/topic/atenciones",
                    Map.of("tipo", tipo,
                            "atencionId", a.getId(),
                            "correlativo", a.getNumeroCorrelativo(),
                            "pasajero", a.getNombre() + "/" + a.getApellido(),
                            "timestamp", DateTimeUtil.ahoraEnLima().toString()));
        } catch (Exception e) {
            log.warn("WebSocket notify failed: {}", e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AtencionResponse> findByRegistroVueloDiarioId(Long registroVueloDiarioId) {
        log.info("Buscando atenciones para registro diario ID: {}", registroVueloDiarioId);

        List<Atencion> atenciones = atencionRepository.findByRegistroVueloDiarioId(registroVueloDiarioId);

        log.info("Encontradas {} atenciones para registro diario", atenciones.size());

        return atenciones.stream()
                .map(atencionMapper::toResponse)
                .toList();
    }
    // ── Métodos migrados desde AtencionController ─────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PnrVerificacionResponse verificarPnr(String pnr, Long vueloId) {
        var atencionExistente = atencionRepository.findByPnrAndVueloId(pnr, vueloId);
        if (atencionExistente.isPresent()) {
            var at = atencionExistente.get();
            String nombreCompleto = at.getApellido() + "/" + at.getNombre();
            return new PnrVerificacionResponse(true, pnr.toUpperCase(), nombreCompleto, at.getNumeroCorrelativo());
        }
        return new PnrVerificacionResponse(false, pnr.toUpperCase(), null, null);
    }

    /**
     * ✅ NUEVO: Construir detalle JSON para auditoría
     */
    private String buildDetalleJson(String correlativo, String pnr, Long registroDiarioId) {
        return String.format(
                "{\"correlativo\":\"%s\",\"pnr\":\"%s\",\"registroDiarioId\":%d}",
                correlativo, pnr, registroDiarioId
        );
    }

    /**
     * NUEVO — Voucher grupal: si el pasajero pertenece a un grupo (mismo
     * PNR/correo compartido) y NO tiene servicios propios (porque los
     * hereda del titular), no se le permite anular/restaurar directamente
     * — debe hacerse desde el detalle del titular, dueño real del servicio
     * compartido. Si el pasajero no pertenece a ningún grupo, o tiene
     * servicios propios (grupo con servicios independientes por pasajero),
     * puede anular/restaurar con normalidad.
     */
    private void validarNoEsAcompanianteDeGrupo(Atencion atencion, String accion) {
        if (atencion.getGrupoId() == null) return;
        boolean tienePropios = !servicioAsignadoRepository
                .findByAtencionId(atencion.getId()).isEmpty();
        if (tienePropios) return;

        String correlativoTitular = atencionRepository
                .findByGrupoIdOrderByIdAsc(atencion.getGrupoId())
                .stream()
                .findFirst()
                .map(Atencion::getNumeroCorrelativo)
                .orElse(null);

        throw new BadRequestException(
                "Este pasajero comparte el voucher grupal con otros — para " + accion
                        + " este registro, hazlo desde el detalle del titular del grupo"
                        + (correlativoTitular != null ? " (" + correlativoTitular + ")." : "."));
    }

    /**
     * NUEVO — Voucher grupal: propaga el nuevo estado (ANULADO/ACTIVO) del
     * titular a los demás pasajeros del grupo, ya que comparten un solo
     * servicio/voucher. No hace nada si la atención de origen no pertenece
     * a ningún grupo.
     */
    private void propagarEstadoAlGrupo(Atencion origen, Long usuarioId,
                                       EstadoAtencionEnum nuevoEstado, String tipoAuditoria) {
        if (origen.getGrupoId() == null) return;

        List<Atencion> grupo = atencionRepository.findByGrupoIdOrderByIdAsc(origen.getGrupoId());
        String eventoWs = nuevoEstado == EstadoAtencionEnum.ANULADO
                ? "ATENCION_ANULADA" : "ATENCION_RESTAURADA";

        for (Atencion miembro : grupo) {
            if (miembro.getId().equals(origen.getId())) continue;
            if (miembro.getEstado() == nuevoEstado) continue;

            miembro.setEstado(nuevoEstado);
            atencionRepository.save(miembro);
            auditoriaService.registrar(
                    usuarioId, tipoAuditoria, "ATENCIONES",
                    String.format(
                            "{\"atencionId\":%d,\"motivo\":\"Propagado desde titular del grupo (%s)\"}",
                            miembro.getId(), origen.getNumeroCorrelativo()),
                    "Atencion", miembro.getId(), miembro.getNumeroCorrelativo());
            notificarWebSocket(eventoWs, miembro);
        }
    }
}