package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.*;
import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.dto.request.*;
import com.saasa.contingencias.domain.dto.response.*;
import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import com.saasa.contingencias.domain.mapping.ProveedorMapper;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.service.IProveedorService;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProveedorServiceImpl implements IProveedorService {

    private final ProveedorRepository proveedorRepository;
    private final ServicioProveedorRepository servicioProveedorRepository;
    private final ProveedorMapper proveedorMapper;
    private final com.saasa.contingencias.config.security.EstacionContext estacionContext;
    private final EstacionLineaAereaRepository estacionLineaAereaRepository;

    public ProveedorServiceImpl(ProveedorRepository proveedorRepository,
                                ServicioProveedorRepository servicioProveedorRepository,ProveedorMapper proveedorMapper, com.saasa.contingencias.config.security.EstacionContext estacionContext, EstacionLineaAereaRepository estacionLineaAereaRepository
    ) {
        this.proveedorRepository = proveedorRepository;
        this.servicioProveedorRepository = servicioProveedorRepository;
        this.proveedorMapper = proveedorMapper;
        this.estacionContext = estacionContext;
        this.estacionLineaAereaRepository = estacionLineaAereaRepository;

    }

    // ─── findAll con filtros opcionales por tipo y estado ────────────────────
    @Override
    public Page<ProveedorResponse> findAll(TipoProveedorEnum tipo, Integer estado, Pageable pageable) {
        // ANTES: `lineaAereaId=null` a propósito ("este listado no filtra por
        // línea aérea de sesión"). AHORA la línea aérea sí es eje de
        // aislamiento: el listado general de proveedores queda acotado al
        // contexto de trabajo activo (estación+línea), igual que el resto.
        Specification<Proveedor> spec = ProveedorSpecification.build(tipo, estado, null);
        Specification<Proveedor> filtroContexto = EstacionSpecifications.<Proveedor>porContextoDelUsuario(
                estacionContext.resolverContextoActivoLectura());
        if (filtroContexto != null) {
            spec = (spec == null) ? filtroContexto : spec.and(filtroContexto);
        }
        Page<Proveedor> page = proveedorRepository.findAll(spec, pageable);
        return page.map(proveedorMapper::toResponse);
    }


    @Override
    @Transactional
    public ProveedorResponse create(ProveedorRequest request) {
        if (proveedorRepository.existsByRuc(request.ruc()))
            throw new BadRequestException("Ya existe un proveedor con RUC: " + request.ruc());
        // ANTES: la línea aérea del proveedor era un campo libre del request
        // (`request.lineaAereaId()`), sin relación con el usuario que lo creaba.
        // AHORA: la línea aérea SIEMPRE es la del contexto de trabajo activo
        // — un usuario en contexto "Lima · Plus Ultra" ya no puede crear un
        // proveedor de LATAM aunque lo mande en el body.
        ScopeEstacionLinea contexto = estacionContext.resolverContextoActivo(request.estacionId(), request.lineaAereaId());
        validarLineaAereaHabilitada(contexto.estacionId(), contexto.lineaAereaId());
        Proveedor p = Proveedor.builder()
                .tipo(request.tipo()).nombre(request.nombre()).ruc(request.ruc())
                .direccion(request.direccion()).telefono(request.telefono())
                .correo(request.correo()).estado(1).build();
        p.setEstacionId(contexto.estacionId());
        p.setLineaAereaId(contexto.lineaAereaId());
        return proveedorMapper.toResponse(proveedorRepository.save(p));
    }
// ─── createConServicios — operación atómica proveedor + servicios ─────────
    /**
     * Crea el proveedor y todos sus servicios tipados en una sola transacción.
     *
     * Lógica de validación cruzada:
     *   HOTEL       → requiere serviciosHotel (al menos un precio > 0)
     *   TRANSPORTE  → requiere serviciosTransporte
     *   RESTAURANTE → requiere serviciosRestaurante
     *
     * Cada servicio con monto > 0 se persiste como un registro en servicios_proveedor
     * con el tipoServicio correspondiente al prototipo:
     *   HOTEL:      HABITACION_SIMPLE, HABITACION_DOBLE, HABITACION_MATRIMONIAL,
     *               HOTEL_DESAYUNO, HOTEL_ALMUERZO, HOTEL_SNACK, HOTEL_CENA
     *   TRANSPORTE: TRANSPORTE_INDIVIDUAL, TRANSPORTE_GRUPAL
     *   RESTAURANTE: RESTAURANTE_DESAYUNO, RESTAURANTE_ALMUERZO, RESTAURANTE_CENA
     */
    @Override
    @Transactional
    public ProveedorConServiciosResponse createConServicios(ProveedorConServiciosRequest request) {
        // Validar RUC único
        if (proveedorRepository.existsByRuc(request.ruc()))
            throw new BadRequestException("Ya existe un proveedor con RUC: " + request.ruc());

        // Validación cruzada tipo ↔ servicios
        validarServiciosPorTipo(request);
        ScopeEstacionLinea contexto = estacionContext.resolverContextoActivo(request.estacionId(), request.lineaAereaId());
        validarLineaAereaHabilitada(contexto.estacionId(), contexto.lineaAereaId());

        // Crear proveedor
        Proveedor p = Proveedor.builder()
                .tipo(request.tipo())
                .nombre(request.nombre())
                .ruc(request.ruc())
                .direccion(request.direccion())
                .telefono(request.telefono())
                .correo(request.correo())
                .estado(1)
                .build();
        p.setEstacionId(contexto.estacionId());
        p.setLineaAereaId(contexto.lineaAereaId());
        p = proveedorRepository.save(p);

        // Crear servicios según tipo
        List<ServicioProveedor> serviciosCreados = switch (request.tipo()) {
            case HOTEL       -> crearServiciosHotel(p, request.serviciosHotel());
            case TRANSPORTE  -> crearServiciosTransporte(p, request.serviciosTransporte());
            case RESTAURANTE -> crearServiciosRestaurante(p, request.serviciosRestaurante());
        };

        List<ServicioProveedor> persistidos = servicioProveedorRepository.saveAll(serviciosCreados);

        return proveedorMapper.toConServiciosResponse(p, persistidos);
    }

    // ─── updateConServicios — operación atómica (NUEVO) ───────────────────────
    /**
     * Actualiza datos básicos del proveedor + servicios con estrategia upsert.
     *
     * Para cada campo de servicio en el request:
     *   NULL  → no hace nada (campo ignorado — permite actualizaciones parciales)
     *   > 0   → busca por tipoServicio: si existe actualiza monto y activa;
     *            si no existe lo crea nuevo
     *   = 0   → busca por tipoServicio: si existe lo desactiva (estado=0);
     *            si no existe no hace nada
     *
     * El tipo y el RUC son inmutables (no se pueden cambiar).
     */
    @Override
    @Transactional
    public ProveedorConServiciosResponse updateConServicios(Long id,
                                                            ActualizarProveedorConServiciosRequest request) {

        Proveedor p = getOrThrow(id);

        // Actualizar datos básicos solo si se envían con valor
        if (request.nombre()    != null && !request.nombre().isBlank())    p.setNombre(request.nombre());
        // ← añadir: RUC actualizable con validación de unicidad
        if (request.ruc() != null && !request.ruc().isBlank()
                && !p.getRuc().equals(request.ruc())) {
            if (proveedorRepository.existsByRuc(request.ruc()))
                throw new BadRequestException("Ya existe un proveedor con RUC: " + request.ruc());
            p.setRuc(request.ruc());
        }
        if (request.direccion() != null && !request.direccion().isBlank()) p.setDireccion(request.direccion());
        if (request.telefono()  != null && !request.telefono().isBlank())  p.setTelefono(request.telefono());
        if (request.correo()    != null && !request.correo().isBlank())    p.setCorreo(request.correo());
        p = proveedorRepository.save(p);

        // Aplicar upsert de servicios según el tipo inmutable del proveedor
        switch (p.getTipo()) {
            case HOTEL       -> actualizarServiciosHotel(p, request.serviciosHotel());
            case TRANSPORTE  -> actualizarServiciosTransporte(p, request.serviciosTransporte());
            case RESTAURANTE -> actualizarServiciosRestaurante(p, request.serviciosRestaurante());
        }

        // Retornar el proveedor con todos sus servicios activos actualizados
        List<ServicioProveedor> activos =
                servicioProveedorRepository.findByProveedorIdAndEstado(id, 1);
        return proveedorMapper.toConServiciosResponse(p, activos);
    }

    // ─── findByIdConServicios ─────────────────────────────────────────────────
    @Override
    public ProveedorConServiciosResponse findByIdConServicios(Long id) {
        Proveedor p = getOrThrow(id);
        List<ServicioProveedor> servicios =
                servicioProveedorRepository.findByProveedorIdAndEstado(id, 1);
        return proveedorMapper.toConServiciosResponse(p, servicios);
    }

    // ─── update ───────────────────────────────────────────────────────────────
    @Override
    @Transactional
    public ProveedorResponse update(Long id, ProveedorRequest request) {
        Proveedor p = getOrThrow(id);
        // Validar unicidad de RUC solo si cambió
        if (!p.getRuc().equals(request.ruc())
                && proveedorRepository.existsByRuc(request.ruc()))
            throw new BadRequestException("Ya existe un proveedor con RUC: " + request.ruc());
        p.setNombre(request.nombre());
        p.setRuc(request.ruc());           // ← añadir
        p.setDireccion(request.direccion());
        p.setTelefono(request.telefono());
        p.setCorreo(request.correo());
        return proveedorMapper.toResponse(proveedorRepository.save(p));
    }

    // ─── changeEstado ─────────────────────────────────────────────────────────
    @Override
    @Transactional
    public void changeEstado(Long id, Integer estado) {
        Proveedor p = getOrThrow(id);
        p.setEstado(estado);
        proveedorRepository.save(p);
    }

    // ─── findServicios ────────────────────────────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public List<ServicioProveedorResponse> findServicios(Long proveedorId) {
        return servicioProveedorRepository.findByProveedorIdAndEstado(proveedorId, 1)
                .stream().map(proveedorMapper::toServicioResponse).toList();
    }

    // ─── addServicio (uno a uno, endpoint existente) ──────────────────────────
    @Override
    @Transactional
    public ServicioProveedorResponse addServicio(Long proveedorId, ServicioProveedorRequest request) {
        Proveedor p = getOrThrow(proveedorId);
        ServicioProveedor sp = ServicioProveedor.builder()
                .proveedor(p)
                .tipoServicio(request.tipoServicio())
                .descripcion(request.descripcion())
                .monto(request.monto())
                .estado(1)
                .build();
        return proveedorMapper.toServicioResponse(servicioProveedorRepository.save(sp));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MÉTODOS DE UPSERT POR TIPO
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Aplica upsert para todos los servicios de un HOTEL.
     * Solo procesa los campos que NO sean null en el request.
     */
    private void actualizarServiciosHotel(Proveedor p, ServiciosHotelRequest h) {
        if (h == null) return; // Si no se envió el bloque, no se toca nada

        upsertServicio(p, "HABITACION_SIMPLE",
                "Habitación simple - 1 persona por habitación",     h.precioHabitacionSimple());
        upsertServicio(p, "HABITACION_DOBLE",
                "Habitación doble - 2 camas individuales",          h.precioHabitacionDoble());
        upsertServicio(p, "HABITACION_MATRIMONIAL",
                "Habitación matrimonial - 1 cama matrimonial",      h.precioHabitacionMatrimonial());
        upsertServicio(p, "HOTEL_DESAYUNO",
                "Desayuno incluido en hotel",                        h.precioDesayuno());
        upsertServicio(p, "HOTEL_ALMUERZO",
                "Almuerzo incluido en hotel",                        h.precioAlmuerzo());
        upsertServicio(p, "HOTEL_SNACK",
                "Snack incluido en hotel",                           h.precioSnack());
        upsertServicio(p, "HOTEL_CENA",
                "Cena incluida en hotel",                            h.precioCena());
    }

    /** Aplica upsert para todos los servicios de TRANSPORTE. */
    private void actualizarServiciosTransporte(Proveedor p, ServiciosTransporteRequest t) {
        if (t == null) return;

        upsertServicio(p, "TRANSPORTE_INDIVIDUAL",
                "Traslado individual aeropuerto-hotel", t.precioTrasladoIndividual());
        upsertServicio(p, "TRANSPORTE_GRUPAL",
                "Transporte grupal de pasajeros",       t.precioTransporteGrupal());
    }

    /** Aplica upsert para todos los servicios de RESTAURANTE. */
    private void actualizarServiciosRestaurante(Proveedor p, ServiciosRestauranteRequest r) {
        if (r == null) return;

        upsertServicio(p, "RESTAURANTE_DESAYUNO",
                "Desayuno en restaurante", r.precioDesayuno());
        upsertServicio(p, "RESTAURANTE_ALMUERZO",
                "Almuerzo en restaurante", r.precioAlmuerzo());
        upsertServicio(p, "RESTAURANTE_CENA",
                "Cena en restaurante",     r.precioCena());
    }

    /**
     * Lógica central del upsert para un servicio individual.
     *
     * monto == null → campo no enviado → no hacer nada
     * monto > 0    → buscar por tipoServicio:
     *                  existe  → actualizar monto + reactivar (estado=1)
     *                  no existe → crear nuevo
     * monto == 0   → buscar por tipoServicio:
     *                  existe  → desactivar (estado=0)
     *                  no existe → ignorar (no crear con monto 0)
     */
    private void upsertServicio(Proveedor p, String tipoServicio,
                                String descripcion, BigDecimal monto) {
        if (monto == null) return; // Campo no enviado → ignorar completamente

        var existente = servicioProveedorRepository
                .findByProveedorIdAndTipoServicio(p.getId(), tipoServicio);

        if (monto.compareTo(BigDecimal.ZERO) > 0) {
            // monto > 0 → crear o actualizar
            if (existente.isPresent()) {
                ServicioProveedor sp = existente.get();
                sp.setMonto(monto);
                sp.setEstado(1); // reactivar si estaba desactivado
                servicioProveedorRepository.save(sp);
            } else {
                ServicioProveedor sp = ServicioProveedor.builder()
                        .proveedor(p)
                        .tipoServicio(tipoServicio)
                        .descripcion(descripcion)
                        .monto(monto)
                        .estado(1)
                        .build();
                servicioProveedorRepository.save(sp);
            }
        } else {
            // monto == 0 → desactivar si existe
            existente.ifPresent(sp -> {
                sp.setEstado(0);
                servicioProveedorRepository.save(sp);
            });
        }
    }

    // ─── Builders de servicios por tipo ──────────────────────────────────────

    /**
     * Construye los ServicioProveedor para un HOTEL.
     * Sección del prototipo imagen 2:
     *   Habitaciones: Simple | Doble | Matrimonial
     *   Alimentación: Desayuno | Almuerzo | Snack | Cena
     * Solo se crean los servicios cuyo monto sea > 0.
     */
    private List<ServicioProveedor> crearServiciosHotel(Proveedor p, ServiciosHotelRequest h) {
        if (h == null)
            throw new BadRequestException("Para tipo HOTEL debe enviar serviciosHotel con los precios");

        List<ServicioProveedor> lista = new ArrayList<>();

        // Habitaciones
        agregarSiMayorACero(lista, p, "HABITACION_SIMPLE",
                "Habitación simple - 1 persona por habitación", h.precioHabitacionSimple());
        agregarSiMayorACero(lista, p, "HABITACION_DOBLE",
                "Habitación doble - 2 camas individuales", h.precioHabitacionDoble());
        agregarSiMayorACero(lista, p, "HABITACION_MATRIMONIAL",
                "Habitación matrimonial - 1 cama matrimonial", h.precioHabitacionMatrimonial());

        // Alimentación
        agregarSiMayorACero(lista, p, "HOTEL_DESAYUNO",
                "Desayuno incluido en hotel", h.precioDesayuno());
        agregarSiMayorACero(lista, p, "HOTEL_ALMUERZO",
                "Almuerzo incluido en hotel", h.precioAlmuerzo());
        agregarSiMayorACero(lista, p, "HOTEL_SNACK",
                "Snack incluido en hotel", h.precioSnack());
        agregarSiMayorACero(lista, p, "HOTEL_CENA",
                "Cena incluida en hotel", h.precioCena());

        if (lista.isEmpty())
            throw new BadRequestException(
                    "Para tipo HOTEL debe configurar al menos un servicio con monto > 0");

        return lista;
    }

    /**
     * Construye los ServicioProveedor para TRANSPORTE.
     * Sección del prototipo imagen 3:
     *   Traslado Individual | Transporte Grupal
     */
    private List<ServicioProveedor> crearServiciosTransporte(Proveedor p, ServiciosTransporteRequest t) {
        if (t == null)
            throw new BadRequestException(
                    "Para tipo TRANSPORTE debe enviar serviciosTransporte con los precios");

        List<ServicioProveedor> lista = new ArrayList<>();
        agregarSiMayorACero(lista, p, "TRANSPORTE_INDIVIDUAL",
                "Traslado individual aeropuerto-hotel", t.precioTrasladoIndividual());
        agregarSiMayorACero(lista, p, "TRANSPORTE_GRUPAL",
                "Transporte grupal de pasajeros", t.precioTransporteGrupal());

        if (lista.isEmpty())
            throw new BadRequestException(
                    "Para tipo TRANSPORTE debe configurar al menos un servicio con monto > 0");

        return lista;
    }

    /**
     * Construye los ServicioProveedor para RESTAURANTE.
     * Sección del prototipo imagen 4:
     *   Desayuno | Almuerzo | Cena
     */
    private List<ServicioProveedor> crearServiciosRestaurante(Proveedor p, ServiciosRestauranteRequest r) {
        if (r == null)
            throw new BadRequestException(
                    "Para tipo RESTAURANTE debe enviar serviciosRestaurante con los precios");

        List<ServicioProveedor> lista = new ArrayList<>();
        agregarSiMayorACero(lista, p, "RESTAURANTE_DESAYUNO",
                "Desayuno en restaurante", r.precioDesayuno());
        agregarSiMayorACero(lista, p, "RESTAURANTE_ALMUERZO",
                "Almuerzo en restaurante", r.precioAlmuerzo());
        agregarSiMayorACero(lista, p, "RESTAURANTE_CENA",
                "Cena en restaurante", r.precioCena());

        if (lista.isEmpty())
            throw new BadRequestException(
                    "Para tipo RESTAURANTE debe configurar al menos un servicio con monto > 0");

        return lista;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Solo agrega el servicio a la lista si el monto es mayor a 0. */
    private void agregarSiMayorACero(List<ServicioProveedor> lista, Proveedor p,
                                     String tipoServicio, String descripcion, BigDecimal monto) {
        if (monto != null && monto.compareTo(BigDecimal.ZERO) > 0) {
            lista.add(ServicioProveedor.builder()
                    .proveedor(p)
                    .tipoServicio(tipoServicio)
                    .descripcion(descripcion)
                    .monto(monto)
                    .estado(1)
                    .build());
        }
    }

    /** Valida que el bloque de servicios corresponda al tipo del proveedor. */
    private void validarServiciosPorTipo(ProveedorConServiciosRequest req) {
        switch (req.tipo()) {
            case HOTEL -> {
                if (req.serviciosHotel() == null)
                    throw new BadRequestException(
                            "Para tipo HOTEL debe enviar el objeto 'serviciosHotel'");
            }
            case TRANSPORTE -> {
                if (req.serviciosTransporte() == null)
                    throw new BadRequestException(
                            "Para tipo TRANSPORTE debe enviar el objeto 'serviciosTransporte'");
            }
            case RESTAURANTE -> {
                if (req.serviciosRestaurante() == null)
                    throw new BadRequestException(
                            "Para tipo RESTAURANTE debe enviar el objeto 'serviciosRestaurante'");
            }
        }
    }

    private Proveedor getOrThrow(Long id) {
        Proveedor p = proveedorRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + id));
        estacionContext.validarAccesoLectura(p);
        return p;
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