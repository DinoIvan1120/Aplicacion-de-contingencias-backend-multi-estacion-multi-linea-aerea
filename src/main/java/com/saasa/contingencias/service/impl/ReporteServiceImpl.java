package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.dto.request.ActualizarPasajeroRequest;
import com.saasa.contingencias.domain.dto.request.ActualizarServiciosRequest;
import com.saasa.contingencias.domain.dto.request.ReporteFilterRequest;
import com.saasa.contingencias.domain.dto.response.ReporteDetalleResponse;
import com.saasa.contingencias.domain.dto.response.ReporteVoucherResponse;
import com.saasa.contingencias.domain.dto.response.ResumenReporteResponse;
import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ReporteServiceImpl implements IReporteService {

    private static final Logger log = LoggerFactory.getLogger(ReporteServiceImpl.class);
    private static final int MAX_ROWS_EXCEL = 10_000;

    private final AtencionRepository atencionRepository;
    private final UsuarioRepository usuarioRepository;
    private final ProveedorRepository proveedorRepository;
    private final ServicioAsignadoRepository servicioAsignadoRepository;
    private final IAuditoriaService auditoriaService;
    private final IPdfGeneratorService pdfGeneratorService;
    private final IAtencionService atencionService;

    private final IS3StorageService s3Service;
    private final IEmailService emailService;

    // ✅ NUEVO: para enviar WhatsApp al regenerar PDF desde detalle
    private final IWhatsAppService whatsAppService;
    private final ReporteExcelBuilder reporteExcelBuilder;
    private final IReporteServicioBuilder reporteServicioBuilder;
    private final com.saasa.contingencias.config.security.EstacionContext estacionContext;


    public ReporteServiceImpl(AtencionRepository atencionRepository,
                              UsuarioRepository usuarioRepository,
                              ProveedorRepository proveedorRepository,
                              ServicioAsignadoRepository servicioAsignadoRepository,
                              IAuditoriaService auditoriaService,
                              IPdfGeneratorService pdfGeneratorService,
                              IAtencionService atencionService,
                              IS3StorageService s3Service,
                              IEmailService emailService,
                              IWhatsAppService whatsAppService,
                              ReporteExcelBuilder reporteExcelBuilder,
                              IReporteServicioBuilder reporteServicioBuilder,
                              com.saasa.contingencias.config.security.EstacionContext estacionContext) {
        this.atencionRepository = atencionRepository;
        this.usuarioRepository = usuarioRepository;
        this.proveedorRepository = proveedorRepository;
        this.servicioAsignadoRepository = servicioAsignadoRepository;
        this.auditoriaService = auditoriaService;
        this.pdfGeneratorService = pdfGeneratorService;
        this.atencionService = atencionService;
        this.s3Service = s3Service;
        this.emailService = emailService;
        this.whatsAppService = whatsAppService;
        this.reporteExcelBuilder = reporteExcelBuilder;
        this.reporteServicioBuilder = reporteServicioBuilder;
        this.estacionContext = estacionContext;

    }

    /**
     * ANTES: armaba `(restringir, estacionIds)` para las 6 consultas
     * nativas — `restringir=0` (Administrador Global) dejaba pasar TODO,
     * sin importar el selector del topbar. AHORA resuelve el par único
     * estación+línea aérea del contexto de trabajo activo (obligatorio
     * también para Administrador Global) y las 6 consultas filtran por
     * igualdad exacta sobre ambos campos.
     */
    private ScopeEstacionLinea contextoActivoParaReportes() {
        return estacionContext.resolverContextoActivoLectura();
    }


    /**
     * Fase 3, sección 11.2: arma los dos parámetros que consumen las 6
     * consultas nativas de AtencionRepository para el filtro por estación.
     * `restringir=0` (Administrador Global) siempre viaja con una lista
     * placeholder no vacía, porque `IN ()` es SQL inválido en MySQL — el
     * corto-circuito `:restringir = 0 OR ...` hace que esa lista ni se
     * evalúe.
     */
    private Object[] paramsFiltroEstacion() {
        List<Long> propias = estacionContext.estacionesActuales();
        if (propias.isEmpty()) {
            return new Object[]{0, List.of(-1L)}; // Administrador Global: sin restricción
        }
        return new Object[]{1, propias};
    }

    /**
     * Fase 4: obtiene la atención validando que el usuario tenga acceso a su
     * estación. Reutilizado en todos los puntos de este servicio que acceden
     * a una Atencion por ID/correlativo directo (findDetalleByAtencionId,
     * findByCorrelativo, actualizarServicios, actualizarPasajero,
     * regenerarYEnviarPdf, regenerarPdfSoloDescarga) — antes de este fix
     * ninguno pasaba por EstacionContext, a diferencia de AtencionServiceImpl.
     */
    private Atencion getOrThrow(Long atencionId) {
        Atencion atencion = atencionRepository.findById(atencionId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No se encontró atención con ID: " + atencionId));
        estacionContext.validarAccesoLectura(atencion);
        return atencion;
    }

    private Atencion getOrThrowPorCorrelativo(String correlativo) {
        Atencion atencion = atencionRepository.findByNumeroCorrelativo(correlativo)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No se encontró voucher con correlativo: " + correlativo));
        estacionContext.validarAccesoLectura(atencion);
        return atencion;
    }

    // ─── resolverAerolinea con filtros por rol + campos (MEJORAS 2 & 3) ────────────────────────
    @Override
    @Transactional(readOnly = true)
    public Page<ReporteVoucherResponse> findAll(ReporteFilterRequest filtros,
                                                String rolUsuario, Long usuarioId, Long proveedorId, Pageable pageable) {
        String aerolineaUsuario = resolverAerolinea(rolUsuario, usuarioId);
        Long proveedorIdFiltro = resolverProveedorId(rolUsuario, usuarioId, proveedorId);

        var spec = AtencionSpecification.build(filtros, rolUsuario, aerolineaUsuario, proveedorIdFiltro);
        var filtroEstacion = com.saasa.contingencias.domain.repository.EstacionSpecifications
                .<Atencion>porContextoDelUsuario(estacionContext.resolverContextoActivoLectura());
        if (filtroEstacion != null) {
            spec = spec.and(filtroEstacion);
        }
        return atencionRepository.findAll(spec, pageable).map(a -> toReporteResponse(a,proveedorIdFiltro));
    }

    // ─── exportarExcel con filtros por rol + campos (MEJORAS 2 & 3) ─────────────────
    @Override
    @Transactional(readOnly = true)
    public byte[] exportarExcel(ReporteFilterRequest filtros,
                                String rolUsuario, Long usuarioId, Long proveedorId) {
        List<ReporteVoucherResponse> datos = obtenerDatosFiltrados(filtros, rolUsuario, usuarioId, proveedorId);
        return reporteExcelBuilder.build(datos, filtros.fechaDesde(), filtros.fechaHasta());
    }
    // ─── Helpers de resolución de contexto por rol ────────────────────────────────────

    /**
     * MEJORA 2 — Resuelve la aerolínea del usuario LINEA_AEREA.
     * El campo 'documento' del usuario almacena el nombre de su aerolínea.
     */
    private String resolverAerolinea(String rolUsuario, Long usuarioId) {
        if (!"LINEA_AEREA".equals(rolUsuario) || usuarioId == null) return null;
        return usuarioRepository.findById(usuarioId)
                .map(u -> u.getCodigoEmpleado())
                .filter(cod -> cod != null && !cod.isBlank())
                .orElse(null);
        //return usuarioRepository.findDocumentoById(usuarioId).orElse(null);
    }

    /**
     * MEJORA 3 — Resuelve el proveedorId del usuario con rol PROVEEDOR.
     * Busca el proveedor activo cuyo correo coincide con el del usuario.
     */
    private Long resolverProveedorId(String rolUsuario, Long usuarioId, Long proveedorIdParam) {
        if (!"PROVEEDOR".equals(rolUsuario) || usuarioId == null) return proveedorIdParam;
        if (proveedorIdParam != null) return proveedorIdParam;
        /*
        return usuarioRepository.findById(usuarioId)
                .map(Usuario::getCorreo)
                .flatMap(correo -> proveedorRepository.findActivoByCorreo(correo))
                .map(Proveedor::getId)
                .orElse(null);*/
        return usuarioRepository.findById(usuarioId)
                .map(u -> {
                    if(u.getCorreo() != null){
                        var byCorreo = proveedorRepository.findActivoByCorreo(u.getCorreo());
                        if(byCorreo.isPresent()) return byCorreo.get().getId();
                    }
                    if(u.getCodigoEmpleado() != null && !u.getCodigoEmpleado().isBlank()){
                        var byRuc = proveedorRepository.findByRucAndEstado(u.getCodigoEmpleado(),1);
                        if(byRuc.isPresent())return byRuc.get().getId();
                    }
                    return null;
                }).orElse(null);
    }

    // ─── Helpers Excel ────────────────────────────────────────────────────────────────

    /**
     * Convierte una Atencion en ReporteVoucherResponse.
     *
     * FIX: Ahora carga los servicios asignados y agrupa por tipo de proveedor
     * para mostrar hotel, transporte y restaurante correctamente.
     */
    private ReporteVoucherResponse toReporteResponse(Atencion a,Long proveedorIdFiltro) {
        // Cargar servicios asignados
        List<ServicioAsignado> servicios = resolverServiciosDe(a);

        // Inicializar valores
        BigDecimal hotelTotal = BigDecimal.ZERO;
        BigDecimal transporteTotal = BigDecimal.ZERO;
        BigDecimal restauranteTotal = BigDecimal.ZERO;
        String hotelNombre = null;
        String transporteNombre = null;
        String restauranteNombre = null;

        // ✅ SIMPLIFICADO: Solo usa vuelo_recurso_id
        for (ServicioAsignado sa : servicios) {
            // Como vuelo_recurso_id es NOT NULL, esto siempre existe
            Proveedor proveedor = sa.getVueloRecurso().getProveedor();

            // ← NUEVO: si hay filtro por proveedor, saltar los servicios de otros proveedores
            if (proveedorIdFiltro != null && !proveedor.getId().equals(proveedorIdFiltro)) {
                continue;
            }

            String tipo = proveedor.getTipo().name();
            BigDecimal monto = sa.getMontoSubtotal() != null ? sa.getMontoSubtotal() : BigDecimal.ZERO;

            switch (tipo) {
                case "HOTEL" -> {
                    hotelTotal = hotelTotal.add(monto);
                    if (hotelNombre == null) hotelNombre = proveedor.getNombre();
                }
                case "TRANSPORTE" -> {
                    transporteTotal = transporteTotal.add(monto);
                    if (transporteNombre == null) transporteNombre = proveedor.getNombre();
                }
                case "RESTAURANTE" -> {
                    restauranteTotal = restauranteTotal.add(monto);
                    if (restauranteNombre == null) restauranteNombre = proveedor.getNombre();
                }
            }
        }

        // NUEVO: también se calcula el nombre del titular, para que la
        // lista muestre a qué grupo pertenece cada pasajero (badge "GRUPAL").
        boolean esTitularDelGrupo = true;
        String nombreTitularGrupo = null;
        if (a.getGrupoId() != null) {
            Atencion titularGrupo = atencionRepository.findTitularByGrupoId(a.getGrupoId()).orElse(a);
            esTitularDelGrupo = a.getId().equals(titularGrupo.getId());
            nombreTitularGrupo = titularGrupo.getNombre() + " " + titularGrupo.getApellido();
        }

        BigDecimal totalVisible = proveedorIdFiltro != null
                ? hotelTotal.add(transporteTotal).add(restauranteTotal)
                : (esTitularDelGrupo
                ? (a.getMontoTotal() != null ? a.getMontoTotal() : BigDecimal.ZERO)
                : BigDecimal.ZERO);

        // Obtener fecha del vuelo desde RegistroVueloDiario
        LocalDate fechaVuelo = null;
        if (a.getRegistroVueloDiario() != null) {
            fechaVuelo = a.getRegistroVueloDiario().getFechaRegistro();
        } else if (a.getVuelo() != null && a.getVuelo().getFechaVuelo() != null) {
            // Fallback: si Vuelo tiene fechaVuelo directamente
            fechaVuelo = a.getVuelo().getFechaVuelo();
        }

        return new ReporteVoucherResponse(
                a.getNumeroCorrelativo(),
                a.getPnr(),
                a.getNombre() + " " + a.getApellido(),
                a.getVuelo() != null ? a.getVuelo().getCodigoVuelo() : "",
                fechaVuelo,
                hotelNombre,
                hotelTotal,
                transporteNombre,
                transporteTotal,
                restauranteNombre,
                restauranteTotal,
                totalVisible,   // ← monto filtrado
                //a.getMontoTotal() != null ? a.getMontoTotal() : BigDecimal.ZERO,
                a.getEstado().name(),
                //a.getAtendidoPor() != null ? a.getAtendidoPor().getNombre() : "",
                a.getAtendidoPor() != null ? a.getAtendidoPor().getNombre() + " " + a.getAtendidoPor().getApellido() : "",
                a.getAtendidoPor() != null && a.getAtendidoPor().getRol() != null
                        ? a.getAtendidoPor().getRol().name() : null,
                a.getCreatedAt(),
                a.getUpdatedAt(),
                a.getGrupoId(),
                esTitularDelGrupo,
                nombreTitularGrupo
        );
    }

    //Agregado

    @Override
    @Transactional(readOnly = true)
    public ReporteDetalleResponse findByCorrelativo(String correlativo, String rolUsuario, Long usuarioId) {
        Atencion atencion = getOrThrowPorCorrelativo(correlativo);

        Long proveedorIdFiltro = resolverProveedorId(rolUsuario, usuarioId, null);
        return buildReporteDetalle(atencion, proveedorIdFiltro);
    }

    @Override
    @Transactional(readOnly = true)
    public ReporteDetalleResponse findDetalleByAtencionId(Long atencionId, String rolUsuario, Long usuarioId) {
        Atencion atencion = getOrThrow(atencionId);

        Long proveedorIdFiltro = resolverProveedorId(rolUsuario, usuarioId, null);
        return buildReporteDetalle(atencion, proveedorIdFiltro);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
// 3. CONSTRUIR DETALLE COMPLETO DEL REPORTE
// ═══════════════════════════════════════════════════════════════════════════════

    private ReporteDetalleResponse buildReporteDetalle(Atencion atencion,Long proveedorIdFiltro ) {
        // Obtener todos los servicios asignados
        List<ServicioAsignado> servicios = resolverServiciosDe(atencion);

        // Separar servicios por tipo
        ReporteDetalleResponse.ServicioDetalleResponse hotel = null;
        ReporteDetalleResponse.ServicioDetalleResponse transporte = null;
        ReporteDetalleResponse.ServicioDetalleResponse restaurante = null;

        for (ServicioAsignado servicio : servicios) {
            switch (servicio.getTipoDetalle()) {
                case HOTEL      -> hotel       = reporteServicioBuilder.buildHotelDetalle(servicio);
                case TRANSPORTE -> transporte  = reporteServicioBuilder.buildTransporteDetalle(servicio);
                case RESTAURANTE -> restaurante = reporteServicioBuilder.buildRestauranteDetalle(servicio);
            }
        }

        // ✅ NUEVO: si quien consulta es un PROVEEDOR, solo puede ver su propio servicio
        if (proveedorIdFiltro != null) {
            if (hotel != null && !proveedorIdFiltro.equals(hotel.proveedorId())) hotel = null;
            if (transporte != null && !proveedorIdFiltro.equals(transporte.proveedorId())) transporte = null;
            if (restaurante != null && !proveedorIdFiltro.equals(restaurante.proveedorId())) restaurante = null;
        }

        Vuelo vuelo = atencion.getVuelo();

        // El total mostrado también debe reflejar solo lo visible para el proveedor
        java.math.BigDecimal totalVisible = proveedorIdFiltro != null
                ? sumaSubtotales(hotel, transporte, restaurante)
                : atencion.getMontoTotal();

        // ✅ NUEVO: Voucher grupal — nombres de todos los pasajeros del
        // mismo grupo (mismo PNR/correo, mismo PDF), para que el detalle
        // deje claro que este voucher se comparte con más pasajeros.
        List<String> pasajerosGrupo = null;
        // NUEVO: solo puede editar servicios quien no pertenece a ningún
        // grupo, o quien ya tiene servicios propios (titular del grupo, o
        // pasajero con servicios independientes dentro de un grupo).
        boolean tieneServiciosPropios = !servicioAsignadoRepository
                .findByAtencionId(atencion.getId()).isEmpty();
        boolean puedeEditarServicios = atencion.getGrupoId() == null || tieneServiciosPropios;
        String correlativoTitularGrupo = null;
        String nombreTitularGrupo = null;
        String apellidoTitularGrupo = null;

        if (atencion.getGrupoId() != null) {
            List<Atencion> grupoAtenciones = atencionRepository
                    .findByGrupoIdOrderByIdAsc(atencion.getGrupoId());
            pasajerosGrupo = grupoAtenciones.stream()
                    .map(a -> a.getNombre() + " " + a.getApellido())
                    .toList();
            if (!puedeEditarServicios && !grupoAtenciones.isEmpty()) {
                Atencion titularGrupo = grupoAtenciones.get(0);
                correlativoTitularGrupo = grupoAtenciones.get(0).getNumeroCorrelativo();
                nombreTitularGrupo = titularGrupo.getNombre();
                apellidoTitularGrupo = titularGrupo.getApellido();
            }
        }

        return new ReporteDetalleResponse(
                atencion.getId(),
                atencion.getRegistroVueloDiario() != null ? atencion.getRegistroVueloDiario().getId() : null,
                atencion.getNumeroCorrelativo(),
                atencion.getPnr(),
                atencion.getCodigoAutorizacion(),
                atencion.getEstado().name(),

                atencion.getNombre(),
                atencion.getApellido(),
                atencion.getCorreo(),
                atencion.getTelefono(),

                vuelo.getId(),
                vuelo.getCodigoVuelo(),
                vuelo.getAerolinea(),
                vuelo.getFechaVuelo(),
                vuelo.getOrigen(),
                vuelo.getDestino(),

                atencion.getCodigoBarras(),
                atencion.getFechaEmision(),
                atencion.getLugarEmision(),

                hotel,
                transporte,
                restaurante,

                totalVisible,

                atencion.getAtendidoPor() != null ?
                        atencion.getAtendidoPor().getNombre() + " " + atencion.getAtendidoPor().getApellido() : null,
                atencion.getAtendidoPor() != null && atencion.getAtendidoPor().getRol() != null
                        ? atencion.getAtendidoPor().getRol().name() : null,
                atencion.getActualizadoPor() != null ?
                        atencion.getActualizadoPor().getNombre() + " " + atencion.getActualizadoPor().getApellido() : null,
                atencion.getActualizadoPor() != null && atencion.getActualizadoPor().getRol() != null
                        ? atencion.getActualizadoPor().getRol().name() : null,
                atencion.getCreatedAt(),
                atencion.getUpdatedAt(),
                atencion.getPdfUrl(),
                atencion.getGrupoId(),
                pasajerosGrupo,
                atencion.getFirmaPasajero(),
                atencion.getFirmaConforme(),
                atencion.getFirmaFecha(),
                atencion.getOrigenFirma() != null ? atencion.getOrigenFirma().name() : null,
                atencion.getOrigenFirmaRol(),
                atencion.getIdiomaVoucher() !=null ? atencion.getIdiomaVoucher().name() : IdiomaVoucherEnum.ES.name(),
                puedeEditarServicios,
                correlativoTitularGrupo,
                nombreTitularGrupo,
                apellidoTitularGrupo


        );
    }

    private java.math.BigDecimal sumaSubtotales(
            ReporteDetalleResponse.ServicioDetalleResponse... servicios) {
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (var s : servicios) {
            if (s != null && s.subtotal() != null) {
                total = total.add(s.subtotal());
            }
        }
        return total;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
// 8. ACTUALIZAR SERVICIOS
// ═══════════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public ReporteDetalleResponse actualizarServicios(Long atencionId,
                                                      ActualizarServiciosRequest request,
                                                      Long usuarioId) {
        Atencion atencion = getOrThrow(atencionId);

        // NUEVO: si esta atención es un acompañante de un voucher grupal
        // (mismo PNR/correo compartido, sin servicios propios porque los
        // usa por herencia del titular vía resolverServiciosDe), la edición
        // debe aplicarse sobre la atención TITULAR — dueña real de los
        // ServicioAsignado — para que el cambio se refleje en todos los
        // pasajeros del grupo. Si el pasajero ya tiene servicios propios
        // (grupo con servicios independientes), se edita normalmente.
        Atencion atencionServicios = resolverAtencionParaEditarServicios(atencion);

        // NUEVO — registra quién realizó la última actualización, para
        // mostrarlo en el detalle del reporte junto a "Generado por".
        Usuario agenteActualiza = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No se encontró usuario con ID: " + usuarioId));

        // Eliminar servicios antiguos
        List<ServicioAsignado> serviciosAntiguos = servicioAsignadoRepository
                .findByAtencionId(atencionServicios.getId());
        servicioAsignadoRepository.deleteAll(serviciosAntiguos);

        // Crear nuevos servicios
        BigDecimal nuevoTotal = BigDecimal.ZERO;

        if (request.hotelVueloRecursoId() != null) {
            nuevoTotal = nuevoTotal.add(reporteServicioBuilder.crearServicioHotel(atencionServicios, request));
        }

        if (request.transporteVueloRecursoId() != null) {
            nuevoTotal = nuevoTotal.add(reporteServicioBuilder.crearServicioTransporte(atencionServicios, request));
        }

        if (request.restauranteVueloRecursoId() != null) {
            nuevoTotal = nuevoTotal.add(reporteServicioBuilder.crearServicioRestaurante(atencionServicios, request));
        }

        // Actualizar total (updatedAt se actualiza automáticamente con @LastModifiedDate)
        atencionServicios.setMontoTotal(nuevoTotal);
        atencionServicios.setActualizadoPor(agenteActualiza);
        atencionRepository.save(atencionServicios);

        // Si la atención consultada (companion de grupo) es distinta de la
        // que realmente guarda los servicios (titular), refleja igual quién
        // actualizó en el registro que se está editando.
        if (!atencion.getId().equals(atencionServicios.getId())) {
            atencion.setActualizadoPor(agenteActualiza);
            atencionRepository.save(atencion);
        }

        // NUEVO: si esta atención es titular de un grupo compartido, hay que
        // propagar el nuevo total a TODOS los demás pasajeros del grupo —
        // cada uno guarda su propio montoTotal (se copió una sola vez al
        // enviar el voucher grupal) y el detalle de cada uno lo lee directo
        // de su fila, no lo recalcula. Sin esto, el acompañante se queda
        // mostrando el monto viejo aunque sus servicios ya se vean al día.
        if(atencionServicios.getGrupoId() != null){
            List<Atencion>grupo = atencionRepository.findByGrupoIdOrderByIdAsc(atencionServicios.getGrupoId());
            for (Atencion miembro: grupo){
                if(!miembro.getId().equals(atencionServicios.getId())){
                    miembro.setMontoTotal(nuevoTotal);
                    miembro.setActualizadoPor(agenteActualiza);
                    atencionRepository.save(miembro);
                }
            }
        }

        // ✅ FIX: Auditoría con firma correcta
        auditoriaService.registrar(
                usuarioId,
                "ACTUALIZAR_SERVICIOS",
                "ATENCIONES",
                String.format("{\"nuevoTotal\":\"%s\",\"atencionId\":%d}", nuevoTotal.toString(), atencionId),
                "ATENCION",
                atencionServicios.getId(),
                atencionServicios.getNumeroCorrelativo()
        );
        log.info("✅ Servicios actualizados para atención {}: nuevo total S/ {}",
                atencionServicios.getId()   , nuevoTotal);

        return buildReporteDetalle(atencion,null);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 8.1 ACTUALIZAR DATOS DEL PASAJERO (solo ADMINISTRADOR y LIDER_SAASA)
    // ═══════════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public ReporteDetalleResponse actualizarPasajero(Long atencionId,
                                                     ActualizarPasajeroRequest request,
                                                     Long usuarioId) {
        Atencion atencion = getOrThrow(atencionId);

        if (request.nombrePasajero() != null) {
            atencion.setNombre(request.nombrePasajero().trim());
        }
        if (request.apellidoPasajero() != null) {
            atencion.setApellido(request.apellidoPasajero().trim());
        }
        if (request.correoPasajero() != null) {
            atencion.setCorreo(request.correoPasajero().trim());
        }
        if (request.telefonoPasajero() != null) {
            String tel = request.telefonoPasajero().trim();
            atencion.setTelefono(tel.isBlank() ? null : tel);
        }
        if (request.pnr() != null) {
            atencion.setPnr(request.pnr().trim().toUpperCase());
        }

        if (request.idiomaVoucher() != null) {
            atencion.setIdiomaVoucher(IdiomaVoucherEnum.valueOf(request.idiomaVoucher().trim().toUpperCase()));
        }
        // NUEVO — registra quién realizó la última actualización, para
        // mostrarlo en el detalle del reporte junto a "Generado por".
        Usuario agenteActualiza = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No se encontró usuario con ID: " + usuarioId));
        atencion.setActualizadoPor(agenteActualiza);

        // updatedAt se actualiza automáticamente con @LastModifiedDate
        atencionRepository.save(atencion);

        auditoriaService.registrar(
                usuarioId,
                "ACTUALIZAR_PASAJERO",
                "ATENCIONES",
                String.format(
                        "{\"atencionId\":%d,\"nombre\":\"%s\",\"apellido\":\"%s\",\"correo\":\"%s\",\"telefono\":\"%s\",\"pnr\":\"%s\"}",
                        atencionId, atencion.getNombre(), atencion.getApellido(),
                        atencion.getCorreo(), atencion.getTelefono(), atencion.getPnr()),
                "ATENCION",
                atencionId,
                atencion.getNumeroCorrelativo()
        );
        log.info("✅ Datos de pasajero actualizados para atención {}", atencionId);

        return buildReporteDetalle(atencion, null);
    }


// ═══════════════════════════════════════════════════════════════════════════════
// 9. REGENERAR Y ENVIAR PDF
// ═══════════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public String regenerarYEnviarPdf(Long atencionId, Long usuarioId,String correoDestino, String telefono,String idiomaVoucher) {
        Atencion atencion = getOrThrow(atencionId);

        // NUEVO — Idioma elegido por el agente en el modal "Enviar PDF al Pasajero".
        // Por defecto se envía en Español (ES) si no se especifica.
        IdiomaVoucherEnum idioma = idiomaVoucher != null && !idiomaVoucher.isBlank()
                ? IdiomaVoucherEnum.valueOf(idiomaVoucher.trim().toUpperCase())
                : IdiomaVoucherEnum.ES;
        atencion.setIdiomaVoucher(idioma);

        // FIX — voucher GRUPAL: si esta atención pertenece a un grupo con más
        // de un integrante, regenerar y reenviar el PDF/correo CONJUNTO (mismo
        // criterio que AtencionVoucherServiceImpl.reenviarVoucherGrupal()).
        // Antes este endpoint ignoraba el grupoId: el PDF y el correo salían
        // con el nombre de un solo pasajero en el encabezado (aunque los
        // servicios sí incluían al grupo completo), perdiendo el "(+N)" del
        // asunto y la lista de pasajeros del cuerpo al reenviar tras un cambio.
        List<Atencion> grupo = (atencion.getGrupoId() != null)
                ? atencionRepository.findByGrupoIdOrderByIdAsc(atencion.getGrupoId())
                : List.of();

        if (grupo.size() > 1) {
            return regenerarYEnviarPdfGrupal(grupo, atencionId, usuarioId, correoDestino, telefono, idioma);
        }

        try {
            // 1. Obtener servicios asignados — usa resolverServiciosDe() en vez de
            //    findByAtencionId() directo: si esta atención es un ACOMPAÑANTE de
            //    un voucher grupal (servicios compartidos, asignados una sola vez
            //    a la titular), findByAtencionId() devuelve una lista VACÍA y el
            //    PDF regenerado sale en blanco (solo encabezado/QR). Con
            //    resolverServiciosDe() se hace fallback a los servicios del grupo
            //    (por grupoId) cuando la atención no tiene servicios propios.
            List<ServicioAsignado> servicios = resolverServiciosDe(atencion);

            // 2. Generar PDF con dos parámetros
            byte[] pdfBytes = pdfGeneratorService.generarVoucher(atencion, servicios);

            // 3. Guardar PDF usando S3StorageService
            //    - En perfil 'dev': guarda localmente en ~/saasa-pdfs/
            //    - En perfil 'prod': guarda en AWS S3
            // 3. Subir PDF actualizado a S3 (sobreescribe el anterior)
            String fileName = atencion.getNumeroCorrelativo() + ".pdf";
            String pdfUrl = s3Service.subirPdf(pdfBytes, fileName);
            log.info("✅ PDF regenerado y subido a S3: {}", pdfUrl);

            // 4. Actualizar URL en atención
            atencion.setPdfUrl(pdfUrl);
            // 5. Actualizar teléfono en BD si el agente lo modificó
            if (telefono != null && !telefono.isBlank()
                    && !telefono.equals(atencion.getTelefono())) {
                atencion.setTelefono(telefono);
            }
            atencionRepository.save(atencion);

            // 5. Enviar por correo (asíncrono)
            // 6. Enviar por email al correo indicado (puede diferir del registrado)
            String nombreCompleto = atencion.getNombre() + " " + atencion.getApellido();

            String destino = (correoDestino != null && !correoDestino.isBlank())
                    ? correoDestino : atencion.getCorreo();
            // FIX — este método (endpoint /regenerar-pdf) representa siempre
            // un reenvío por ACTUALIZACIÓN del PDF (el pasajero ya tenía
            // servicios asignados y se están regenerando/reenviando tras un
            // cambio), por lo que el mensaje debe indicar "actualizados" y
            // no repetir el texto de la asignación inicial.
            emailService.enviarVoucherActualizado(destino,null, atencion.getNumeroCorrelativo(), pdfBytes, nombreCompleto, idioma);
            log.info("📧 Email enviado a: {}", destino);

        // 7. Enviar por WhatsApp si se proporcionó teléfono
        // FIX — usar reenviarVoucherWhatsApp() (mensaje "actualizados")
        // en vez de enviarVoucherWhatsApp() (mensaje "asignados"): antes
        // este flujo de actualización mandaba el mismo texto que el
        // primer envío.
            if (telefono != null && !telefono.isBlank()) {
                whatsAppService.reenviarVoucherWhatsApp(atencionId, telefono, usuarioId,idioma);
                log.info("📱 WhatsApp enviado a: {}", telefono);
            }

            // 6. Registrar en auditoría
            auditoriaService.registrar(
                    usuarioId,
                    "REGENERAR_PDF",
                    "ATENCIONES",
                    String.format("{\"correlativo\":\"%s\",\"pdfUrl\":\"%s\",\"correo\":\"%s\"}",
                            atencion.getNumeroCorrelativo(), pdfUrl, atencion.getCorreo()),
                    "ATENCION",
                    atencionId,
                    atencion.getNumeroCorrelativo()

            );

            log.info("✅ PDF regenerado y enviado para atención {}: {}",
                    atencionId, atencion.getNumeroCorrelativo());

            return pdfUrl;

        } catch (Exception e) {
            log.error("❌ Error al regenerar PDF para atención {}: {}", atencionId, e.getMessage());
            throw new RuntimeException("Error al regenerar PDF: " + e.getMessage(), e);
        }
    }

    /**
     * NUEVO — FIX: rama grupal de regenerarYEnviarPdf(). Reconstruye el PDF
     * con los datos ACTUALES de TODOS los integrantes del grupo (refleja
     * cualquier edición reciente de cualquiera de ellos, sea el titular o no)
     * y lo reenvía como UN solo correo conjunto.
     */
    private String regenerarYEnviarPdfGrupal(List<Atencion> atenciones, Long atencionIdOrigen, Long usuarioId,
                                             String correoDestino, String telefono, IdiomaVoucherEnum idioma) {
        Atencion titular = atenciones.get(0);
        for (Atencion a : atenciones) a.setIdiomaVoucher(idioma);

        boolean compartidos = atenciones.stream()
                .skip(1)
                .allMatch(a -> servicioAsignadoRepository.findByAtencionId(a.getId()).isEmpty());

        Map<Long, List<ServicioAsignado>> serviciosPorAtencion = new HashMap<>();
        if (compartidos) {
            List<ServicioAsignado> compartidosServicios = resolverServiciosDe(titular);
            for (Atencion a : atenciones) serviciosPorAtencion.put(a.getId(), compartidosServicios);
        } else {
            for (Atencion a : atenciones)
                serviciosPorAtencion.put(a.getId(), servicioAsignadoRepository.findByAtencionId(a.getId()));
        }

        byte[] pdfBytes = pdfGeneratorService.generarVoucherGrupal(atenciones, serviciosPorAtencion, compartidos);

        String pdfUrl = s3Service.subirPdf(pdfBytes, "GRUPO-" + titular.getNumeroCorrelativo() + ".pdf");
        for (Atencion a : atenciones) a.setPdfUrl(pdfUrl);
        if (telefono != null && !telefono.isBlank()) {
            atenciones.stream().filter(a -> a.getId().equals(atencionIdOrigen))
                    .findFirst().ifPresent(a -> a.setTelefono(telefono));
        }
        atencionRepository.saveAll(atenciones);

        String correoFinal = (correoDestino != null && !correoDestino.isBlank()) ? correoDestino : titular.getCorreo();
        List<String> nombres = atenciones.stream()
                .map(a -> a.getNombre() + " " + a.getApellido()).collect(Collectors.toList());
        String correlativoGrupo = titular.getNumeroCorrelativo() + " (+" + (atenciones.size() - 1) + ")";

        emailService.reenviarVoucherGrupal(correoFinal, null, correlativoGrupo, pdfBytes, nombres, idioma);

        if (telefono != null && !telefono.isBlank()) {
            whatsAppService.reenviarVoucherWhatsApp(atencionIdOrigen, telefono, usuarioId, idioma);
        }

        auditoriaService.registrar(usuarioId, "REGENERAR_PDF_GRUPAL", "ATENCIONES",
                String.format("{\"grupoId\":\"%s\",\"pdfUrl\":\"%s\",\"correo\":\"%s\",\"pasajeros\":%d}",
                        titular.getGrupoId(), pdfUrl, correoFinal, atenciones.size()),
                "ATENCION", atencionIdOrigen, correlativoGrupo);

        return pdfUrl;
    }

    @Override
    @Transactional
    public String regenerarPdfSoloDescarga(Long atencionId) {
        Atencion atencion = getOrThrow(atencionId);

        // FIX — mismo criterio grupal que regenerarYEnviarPdf().
        List<Atencion> grupo = (atencion.getGrupoId() != null)
                ? atencionRepository.findByGrupoIdOrderByIdAsc(atencion.getGrupoId())
                : List.of();

        if (grupo.size() > 1) {
            return regenerarPdfSoloDescargaGrupal(grupo);
        }

        try {
            // Mismo fix que en regenerarYEnviarPdf: usar resolverServiciosDe()
            // para que un acompañante de voucher grupal también descargue el
            // PDF completo (con los servicios del grupo) y no uno vacío.
            List<ServicioAsignado> servicios = resolverServiciosDe(atencion);

            byte[] pdfBytes = pdfGeneratorService.generarVoucher(atencion, servicios);

            String fileName = atencion.getNumeroCorrelativo() + ".pdf";
            String pdfUrl = s3Service.subirPdf(pdfBytes, fileName);

            // Actualizar URL — así el S3 queda sincronizado con los datos actuales
            atencion.setPdfUrl(pdfUrl);
            atencionRepository.save(atencion);

            log.info("✅ PDF regenerado (solo descarga, sin email) para atención {}", atencionId);

            // Devolver URL firmada temporal directamente
            return s3Service.generarUrlFirmada(pdfUrl);

        } catch (Exception e) {
            log.error("❌ Error regenerando PDF para descarga {}: {}", atencionId, e.getMessage());
            throw new RuntimeException("Error al regenerar PDF: " + e.getMessage(), e);
        }
    }

    /**
     * NUEVO — FIX: rama grupal de regenerarPdfSoloDescarga(). Misma lógica
     * de "compartidos" que regenerarYEnviarPdfGrupal(), sin enviar correo ni
     * WhatsApp — solo regenera, sube a S3 y devuelve la URL firmada.
     */
    private String regenerarPdfSoloDescargaGrupal(List<Atencion> atenciones) {
        Atencion titular = atenciones.get(0);
        boolean compartidos = atenciones.stream()
                .skip(1)
                .allMatch(a -> servicioAsignadoRepository.findByAtencionId(a.getId()).isEmpty());

        Map<Long, List<ServicioAsignado>> serviciosPorAtencion = new HashMap<>();
        if (compartidos) {
            List<ServicioAsignado> compartidosServicios = resolverServiciosDe(titular);
            for (Atencion a : atenciones) serviciosPorAtencion.put(a.getId(), compartidosServicios);
        } else {
            for (Atencion a : atenciones)
                serviciosPorAtencion.put(a.getId(), servicioAsignadoRepository.findByAtencionId(a.getId()));
        }

        byte[] pdfBytes = pdfGeneratorService.generarVoucherGrupal(atenciones, serviciosPorAtencion, compartidos);
        String pdfUrl = s3Service.subirPdf(pdfBytes, "GRUPO-" + titular.getNumeroCorrelativo() + ".pdf");
        for (Atencion a : atenciones) a.setPdfUrl(pdfUrl);
        atencionRepository.saveAll(atenciones);

        return s3Service.generarUrlFirmada(pdfUrl);
    }

    @Override
    public void anularPorCorrelativo(String correlativo, Long usuarioId) {
        Atencion a = atencionRepository.findByNumeroCorrelativo(correlativo)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "Reporte no encontrado: " + correlativo));
        atencionService.anular(a.getId(), usuarioId);
    }

    @Override
    public void restaurarPorCorrelativo(String correlativo, Long usuarioId) {
        Atencion a = atencionRepository.findByNumeroCorrelativo(correlativo)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "Reporte no encontrado: " + correlativo));
        atencionService.restaurar(a.getId(), usuarioId);
    }

    @Override
    public ResumenReporteResponse getResumen(LocalDate fechaDesde,
                                             LocalDate fechaHasta,
                                             String rolUsuario,
                                             Long usuarioId) {

        LocalDateTime inicio = (fechaDesde != null ? fechaDesde : LocalDate.now().minusMonths(1))
                .atStartOfDay();
        LocalDateTime fin = (fechaHasta != null ? fechaHasta : LocalDate.now())
                .atTime(23, 59, 59);

        // Contexto de trabajo activo (estación+línea aérea) para las 6 consultas nativas.
        ScopeEstacionLinea contexto = contextoActivoParaReportes();

        // ── KPIs ──────────────────────────────────────────────────────────────────
        Long totalAtenciones = atencionRepository.countActivosByFechaRange(
                inicio, fin, contexto.estacionId(), contexto.lineaAereaId());
        if (totalAtenciones == null) totalAtenciones = 0L;

        BigDecimal importeTotal = atencionRepository.sumMontoByFechaRange(
                inicio, fin, contexto.estacionId(), contexto.lineaAereaId());
        if (importeTotal == null) importeTotal = BigDecimal.ZERO;

        BigDecimal promedio = totalAtenciones > 0
                ? importeTotal.divide(BigDecimal.valueOf(totalAtenciones), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // ── Atenciones por fecha ───────────────────────────────────────────────────
        List<ResumenReporteResponse.FechaStat> atencionPorFecha =
                atencionRepository.countByFecha(inicio, fin, contexto.estacionId(), contexto.lineaAereaId()).stream()
                        .map(row -> new ResumenReporteResponse.FechaStat(
                                row[0].toString(),
                                ((Number) row[1]).longValue()
                        ))
                        .toList();

        // ── Importe por fecha ──────────────────────────────────────────────────────
        List<ResumenReporteResponse.FechaImporteStat> importePorFecha =
                atencionRepository.importeByFecha(inicio, fin, contexto.estacionId(), contexto.lineaAereaId()).stream()
                        .map(row -> new ResumenReporteResponse.FechaImporteStat(
                                row[0].toString(),
                                new BigDecimal(row[1].toString())
                        ))
                        .toList();

        // ── Distribución por tipo de servicio ─────────────────────────────────────
        List<ResumenReporteResponse.TipoServicioStat> distribucionPorTipo =
                atencionRepository.distribucionByTipo(inicio, fin, contexto.estacionId(), contexto.lineaAereaId()).stream()
                        .map(row -> new ResumenReporteResponse.TipoServicioStat(
                                row[0].toString(),
                                ((Number) row[1]).longValue(),
                                new BigDecimal(row[2].toString())
                        ))
                        .toList();

        // ── Distribución por proveedor individual ──────────────────────────────────
        List<ResumenReporteResponse.ProveedorStat> distribucionPorProveedor =
                atencionRepository.distribucionByProveedor(inicio, fin, contexto.estacionId(), contexto.lineaAereaId()).stream()
                        .map(row -> new ResumenReporteResponse.ProveedorStat(
                                row[0].toString(),
                                row[1].toString(),
                                ((Number) row[2]).longValue(),
                                new BigDecimal(row[3].toString())
                        ))
                        .toList();

        return new ResumenReporteResponse(
                totalAtenciones,
                importeTotal,
                promedio,
                distribucionPorTipo,
                atencionPorFecha,
                importePorFecha,
                distribucionPorProveedor
        );
    }

    private List<ReporteVoucherResponse> obtenerDatosFiltrados(
            ReporteFilterRequest filtros, String rolUsuario, Long usuarioId, Long proveedorId) {
        String aerolineaUsuario = resolverAerolinea(rolUsuario, usuarioId);
        Long proveedorIdFiltro = resolverProveedorId(rolUsuario, usuarioId, proveedorId); // ← ya no pasa null
        var spec = AtencionSpecification.build(filtros, rolUsuario, aerolineaUsuario, proveedorIdFiltro);
        var filtroEstacion = com.saasa.contingencias.domain.repository.EstacionSpecifications
                .<Atencion>porContextoDelUsuario(estacionContext.resolverContextoActivoLectura());
        if (filtroEstacion != null) {
            spec = spec.and(filtroEstacion);
        }
        return atencionRepository.findAll(spec).stream()
                .map(a -> toReporteResponse(a, proveedorIdFiltro))
                .toList();
    }

    /**
     * NUEVO — Voucher grupal: busca los servicios propios de la atención;
     * si no tiene ninguno (porque pertenece a un grupo — "un_correo" o
     * "correo_individual" — cuyos servicios se asignaron UNA sola vez a la
     * atención "titular" para no descontar la disponibilidad varias veces)
     * y tiene grupoId, busca los del grupo. Así el reporte (lista y
     * detalle) de CUALQUIER pasajero del grupo muestra el mismo hotel,
     * monto y detalle que ve el titular — igual que el PDF grupal enviado.
     */
    private List<ServicioAsignado> resolverServiciosDe(Atencion atencion) {
        List<ServicioAsignado> propios = servicioAsignadoRepository.findByAtencionId(atencion.getId());
        if (!propios.isEmpty() || atencion.getGrupoId() == null) {
            return propios;
        }
        return servicioAsignadoRepository.findByAtencionGrupoId(atencion.getGrupoId());
    }

    /**
     * NUEVO — Resuelve sobre qué atención deben crearse/editarse los
     * ServicioAsignado: si el pasajero consultado pertenece a un grupo y NO
     * tiene servicios propios (porque comparte PNR/correo y los hereda del
     * titular), la edición se redirige al titular del grupo.
     */
    private Atencion resolverAtencionParaEditarServicios(Atencion atencion) {
        if (atencion.getGrupoId() == null) return atencion;
        boolean tienePropios = !servicioAsignadoRepository
                .findByAtencionId(atencion.getId()).isEmpty();
        if (tienePropios) return atencion;
        return atencionRepository.findByGrupoIdOrderByIdAsc(atencion.getGrupoId())
                .stream()
                .findFirst()
                .orElse(atencion);
    }

}
