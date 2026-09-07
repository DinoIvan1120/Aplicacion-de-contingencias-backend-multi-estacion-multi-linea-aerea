package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.*;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.dto.request.*;
import com.saasa.contingencias.domain.dto.response.VueloRecursoResponse;
import com.saasa.contingencias.domain.dto.response.VueloResponse;
import com.saasa.contingencias.domain.enumeration.*;
import com.saasa.contingencias.domain.mapping.VueloMapper;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.util.DateTimeUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VueloServiceImplTest {

    @Mock VueloRepository vueloRepository;
    @Mock VueloRecursoRepository vueloRecursoRepository;
    @Mock UsuarioRepository usuarioRepository;
    @Mock ProveedorRepository proveedorRepository;
    @Mock AtencionRepository atencionRepository;
    @Mock EstacionContext estacionContext;
    @Mock LineaAereaRepository lineaAereaRepository;
    @Mock EstacionLineaAereaRepository estacionLineaAereaRepository;
    @Mock EstacionRepository estacionRepository;
    @InjectMocks VueloServiceImpl vueloService;
    @Mock
    VueloMapper vueloMapper;

    private Usuario lider;
    private LocalDate fechaFutura;    // siempre futura para evitar flakiness temporal
    private LocalDate fechaPasada;

    // Contexto de trabajo activo (estación+línea aérea) por defecto para los tests
    // que no son específicamente sobre la resolución del contexto.
    private static final Long ESTACION_ID = 1L;
    private static final Long LINEA_AEREA_ID = 1L;

    @BeforeEach
    void setUp() {
        lider = Usuario.builder().id(1L).nombre("Lider").apellido("SAASA").correo("lider@saasa.com").build();
        fechaFutura = DateTimeUtil.hoyEnLima().plusDays(5);
        fechaPasada = DateTimeUtil.hoyEnLima().minusDays(1);

        // Fase 3/4: create()/crearRegistroCompleto() resuelven el contexto de
        // trabajo activo (estación+línea aérea) vía EstacionContext y validan
        // que la aerolínea del request coincida con el nombre de esa línea
        // aérea — se deja "PlusUltra" por defecto para no romper los tests
        // que no son específicamente sobre esta validación.
        LineaAerea lineaExistente = LineaAerea.builder().id(LINEA_AEREA_ID).nombre("PlusUltra").estado(1).build();
        lenient().when(estacionContext.resolverContextoActivo(any(), any()))
                .thenReturn(new ScopeEstacionLinea(ESTACION_ID, LINEA_AEREA_ID));
        lenient().when(estacionContext.resolverContextoActivo())
                .thenReturn(new ScopeEstacionLinea(ESTACION_ID, LINEA_AEREA_ID));
        // findAll/buscar/obtenerItinerarioHoy (lecturas) usan
        // resolverContextoActivoLectura() desde el fix de historial
        // multi-estación — mismo valor por defecto, método distinto.
        lenient().when(estacionContext.resolverContextoActivoLectura())
                .thenReturn(new ScopeEstacionLinea(ESTACION_ID, LINEA_AEREA_ID));
        lenient().when(lineaAereaRepository.findById(LINEA_AEREA_ID))
                .thenReturn(Optional.of(lineaExistente));
    }

    @Test
    void create_origenIataInvalido_lanzaBadRequest() {
        VueloRequest req = new VueloRequest("PU", "PU302", "LIM", "BOGX", LocalDate.now(), ContingenciaEnum.CANCELACION, "", null, null);
        assertThrows(BadRequestException.class, () -> vueloService.create(req, 1L));
    }

    @Test
    void create_datosValidos_retornaVuelo() {
        VueloRequest req = new VueloRequest("PlusUltra", "PU302", "LIM", "BOG", LocalDate.now(), ContingenciaEnum.CANCELACION, "", null, null);
        Vuelo saved = Vuelo.builder()
                .id(1L).aerolinea("PlusUltra").codigoVuelo("PU302")
                .origen("LIM").destino("BOG").fechaVuelo(LocalDate.now())
                .tipoContingencia(ContingenciaEnum.CANCELACION)
                .estado(EstadoVueloEnum.ACTIVO).creadoPor(lider).build();
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(lider));
        when(vueloRepository.save(any())).thenReturn(saved);
        when(vueloMapper.toResponse(saved)).thenReturn(
                new VueloResponse(1L, "PlusUltra", "PU302", "LIM", "BOG", LocalDate.now(),
                        "CANCELACION", "", "ACTIVO", 1L, "Lider SAASA", null));
        var result = vueloService.create(req, 1L);
        assertNotNull(result);
        assertEquals("PU302", result.codigoVuelo());
        assertEquals("CANCELACION", result.tipoContingencia());
    }

    @Test
    void create_destinoIataInvalido_lanzaBadRequest() {
        VueloRequest req = new VueloRequest("PU", "PU302", "LIMA", "BOG", LocalDate.now(), ContingenciaEnum.CANCELACION, "", null, null);
        assertThrows(BadRequestException.class, () -> vueloService.create(req, 1L));
    }

    @Test
    void anular_vueloConAtenciones_lanzaBadRequest() {
        Vuelo vuelo = Vuelo.builder().id(1L).estado(EstadoVueloEnum.ACTIVO).build();
        when(vueloRepository.findById(1L)).thenReturn(Optional.of(vuelo));
        when(atencionRepository.existsByVueloId(1L)).thenReturn(true);
        assertThrows(BadRequestException.class, () -> vueloService.anular(1L));
    }

    @Test
    void anular_sinAtenciones_cambiaEstado() {
        Vuelo vuelo = Vuelo.builder().id(1L).estado(EstadoVueloEnum.ACTIVO).build();
        when(vueloRepository.findById(1L)).thenReturn(Optional.of(vuelo));
        when(atencionRepository.existsByVueloId(1L)).thenReturn(false);
        when(vueloRepository.save(any())).thenReturn(vuelo);
        assertDoesNotThrow(() -> vueloService.anular(1L));
        assertEquals(EstadoVueloEnum.ANULADO, vuelo.getEstado());
    }

    // ─── VueloRecursoRequest tiene 5 campos: (proveedorId, simples, dobles, matrimoniales, capacidadTotal)

    @Test
    void habilitarRecurso_proveedorInactivo_lanzaProveedorInactivo() {
        Vuelo vuelo = Vuelo.builder().id(1L).estado(EstadoVueloEnum.ACTIVO).build();
        Proveedor p = Proveedor.builder().id(5L).estado(0).build();
        when(vueloRepository.findById(1L)).thenReturn(Optional.of(vuelo));
        when(proveedorRepository.findById(5L)).thenReturn(Optional.of(p));
        // 5 campos: proveedorId, simples, dobles, matrimoniales, capacidadTotal
        VueloRecursoRequest req = new VueloRecursoRequest(5L, null, null, null, null);
        assertThrows(ProveedorInactivoException.class, () -> vueloService.habilitarRecurso(1L, req, 1L));
    }

    @Test
    void habilitarRecurso_hotelSinHabitaciones_lanzaBadRequest() {
        Vuelo vuelo = Vuelo.builder().id(1L).estado(EstadoVueloEnum.ACTIVO).build();
        Proveedor hotel = Proveedor.builder().id(2L).estado(1)
                .tipo(TipoProveedorEnum.HOTEL).nombre("Hotel Test").build();
        when(vueloRepository.findById(1L)).thenReturn(Optional.of(vuelo));
        when(proveedorRepository.findById(2L)).thenReturn(Optional.of(hotel));
        when(vueloRecursoRepository.existsByVueloIdAndProveedorId(1L, 2L)).thenReturn(false);
        // Todos los tipos de habitación en null → total = 0 → BadRequest
        VueloRecursoRequest req = new VueloRecursoRequest(2L, null, null, null, null);
        assertThrows(BadRequestException.class, () -> vueloService.habilitarRecurso(1L, req, 1L));
    }

    @Test
    void habilitarRecurso_hotelConHabitaciones_retornaResumen() {
        Vuelo vuelo = Vuelo.builder().id(1L).estado(EstadoVueloEnum.ACTIVO).build();
        Proveedor hotel = Proveedor.builder().id(2L).estado(1)
                .tipo(TipoProveedorEnum.HOTEL).nombre("Hotel Costa del Sol").build();
        VueloRecurso vrSaved = VueloRecurso.builder()
                .id(10L).vuelo(vuelo).proveedor(hotel)
                .habitacionesSimples(10).habitacionesDobles(20).habitacionesMatrimoniales(4)
                .capacidadTotal(null)
                .habilitadoPor(lider).habilitadoEn(LocalDateTime.now()).estado(1).build();

        when(vueloRepository.findById(1L)).thenReturn(Optional.of(vuelo));
        when(proveedorRepository.findById(2L)).thenReturn(Optional.of(hotel));
        when(vueloRecursoRepository.existsByVueloIdAndProveedorId(1L, 2L)).thenReturn(false);
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(lider));
        when(vueloRecursoRepository.save(any())).thenReturn(vrSaved);

        when(vueloMapper.toRecursoResponse(vrSaved)).thenReturn(
                new VueloRecursoResponse(10L, 1L, 2L, "Hotel Costa del Sol", "HOTEL",null,
                        10, 20, 4, 34, null, "Lider SAASA", vrSaved.getHabilitadoEn(), 1));

        // simples=10, dobles=20, matrimoniales=4
        VueloRecursoRequest req = new VueloRecursoRequest(2L, 10, 20, 4, null);
        var result = vueloService.habilitarRecurso(1L, req, 1L);

        assertNotNull(result);
        assertEquals(10, result.habitacionesSimples());
        assertEquals(20, result.habitacionesDobles());
        assertEquals(4,  result.habitacionesMatrimoniales());
        assertEquals(34, result.totalHabitaciones());   // 10+20+4 = 34
    }

    @Test
    void habilitarRecurso_transporte_usaCapacidadTotal() {
        Vuelo vuelo = Vuelo.builder().id(1L).estado(EstadoVueloEnum.ACTIVO).build();
        Proveedor transporte = Proveedor.builder().id(3L).estado(1)
                .tipo(TipoProveedorEnum.TRANSPORTE).nombre("TransLima").build();
        VueloRecurso vrSaved = VueloRecurso.builder()
                .id(11L).vuelo(vuelo).proveedor(transporte)
                .habitacionesSimples(null).habitacionesDobles(null).habitacionesMatrimoniales(null)
                .capacidadTotal(5)
                .habilitadoPor(lider).habilitadoEn(LocalDateTime.now()).estado(1).build();

        when(vueloRepository.findById(1L)).thenReturn(Optional.of(vuelo));
        when(proveedorRepository.findById(3L)).thenReturn(Optional.of(transporte));
        when(vueloRecursoRepository.existsByVueloIdAndProveedorId(1L, 3L)).thenReturn(false);
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(lider));
        when(vueloRecursoRepository.save(any())).thenReturn(vrSaved);

        when(vueloMapper.toRecursoResponse(vrSaved)).thenReturn(
                new VueloRecursoResponse(11L, 1L, 3L, "TransLima", "TRANSPORTE",null,
                        null, null, null, 0, 5, "Lider SAASA", vrSaved.getHabilitadoEn(), 1));

        // Para transporte solo capacidadTotal
        VueloRecursoRequest req = new VueloRecursoRequest(3L, null, null, null, 5);
        var result = vueloService.habilitarRecurso(1L, req, 1L);

        assertNotNull(result);
        assertNull(result.habitacionesSimples());
        assertNull(result.habitacionesDobles());
        assertNull(result.habitacionesMatrimoniales());
        assertEquals(5, result.capacidadTotal());
    }

    // ════════════════════════════════════════════════════════════════════════
    // 10. DateTimeUtil — habilitarEn usa hora Lima, no UTC
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("habilitarRecurso — habilitado En se asigna con ahoraEnLima(), no LocalDateTime.now()")
    void habilitarRecurso_habilitadoEn_usaHoraLima() {
        Vuelo     vuelo      = vueloConId(1L, "PU302", fechaFutura);
        Proveedor transporte = proveedorTransporte(3L, "TransLima");

        // Capturamos el argumento que se le pasa a save()
        ArgumentCaptor<VueloRecurso> captor = ArgumentCaptor.forClass(VueloRecurso.class);

        LocalDateTime antesLlama = DateTimeUtil.ahoraEnLima();

        VueloRecurso vrSaved = VueloRecurso.builder()
                .id(20L).vuelo(vuelo).proveedor(transporte)
                .capacidadTotal(3).habilitadoPor(lider)
                .habilitadoEn(DateTimeUtil.ahoraEnLima()).estado(1).build();

        when(vueloRepository.findById(1L)).thenReturn(Optional.of(vuelo));
        when(proveedorRepository.findById(3L)).thenReturn(Optional.of(transporte));
        when(vueloRecursoRepository.existsByVueloIdAndProveedorId(1L, 3L)).thenReturn(false);
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(lider));
        when(vueloRecursoRepository.save(captor.capture())).thenReturn(vrSaved);

        vueloService.habilitarRecurso(1L, new VueloRecursoRequest(3L, null, null, null, 3), 1L);

        LocalDateTime despuesLlama = DateTimeUtil.ahoraEnLima();
        LocalDateTime habilitadoEn = captor.getValue().getHabilitadoEn();

        // habilitadoEn debe estar entre antesLlama y despuesLlama
        assertNotNull(habilitadoEn);
        assertFalse(habilitadoEn.isBefore(antesLlama),
                "habilitadoEn no debe ser anterior al momento de la llamada");
        assertFalse(habilitadoEn.isAfter(despuesLlama),
                "habilitadoEn no debe ser posterior al momento de la llamada");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Fase 4/multi-estación — findAll/buscar delegan en EstacionSpecifications
    // usando el contexto de trabajo activo (estación+línea aérea) ya resuelto
    // por EstacionContext.resolverContextoActivo(), getOrThrow (vía
    // update/anular/habilitar) delega en validarAccesoLectura, y
    // create()/crearRegistroCompleto() delegan en
    // resolverContextoActivo(estacionId, lineaAereaId). El filtrado real (que
    // la Specification excluya filas de otra estación/línea) se prueba con DB
    // real en EstacionScopeSpecificationIntegrationTest — aquí solo se
    // verifica que VueloServiceImpl efectivamente consulta y respeta
    // EstacionContext.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void findAll_consultaContextoActivoYDelegaFiltroAlRepositorio() {
        when(estacionContext.resolverContextoActivoLectura())
                .thenReturn(new ScopeEstacionLinea(ESTACION_ID, LINEA_AEREA_ID));
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        when(vueloRepository.findAll(
                org.mockito.ArgumentMatchers.<Specification<Vuelo>>any(),
                eq(pageable)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        vueloService.findAll(pageable);

        verify(estacionContext).resolverContextoActivoLectura();
        verify(vueloRepository).findAll(
                org.mockito.ArgumentMatchers.<Specification<Vuelo>>any(),
                eq(pageable));
    }

    @Test
    void buscar_tambienConsultaContextoActivo() {
        when(estacionContext.resolverContextoActivoLectura())
                .thenReturn(new ScopeEstacionLinea(ESTACION_ID, LINEA_AEREA_ID));
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        when(vueloRepository.findAll(
                org.mockito.ArgumentMatchers.<Specification<Vuelo>>any(),
                eq(pageable)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        vueloService.buscar(null, null, null, null, null, null, pageable);

        verify(estacionContext).resolverContextoActivoLectura();
    }

    @Test
    @DisplayName("findAll — REGRESIÓN historial: lineaAereaId null en el contexto no rompe (todas las líneas de la estación)")
    void findAll_sinLineaAereaEnElContexto_noLanzaYDelegaFiltroSoloPorEstacion() {
        // Antes del fix, EstacionContext.resolverContextoActivo() exigía
        // línea aérea también para lecturas: un usuario multi-estación en
        // modo "sin filtrar por línea" recibía 400 acá también, no solo en
        // registros diarios.
        when(estacionContext.resolverContextoActivoLectura())
                .thenReturn(new ScopeEstacionLinea(ESTACION_ID, null));
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        when(vueloRepository.findAll(
                org.mockito.ArgumentMatchers.<Specification<Vuelo>>any(),
                eq(pageable)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        assertDoesNotThrow(() -> vueloService.findAll(pageable));

        verify(vueloRepository).findAll(
                org.mockito.ArgumentMatchers.<Specification<Vuelo>>any(),
                eq(pageable));
    }

    @Test
    void update_delegaValidacionDeAccesoDeEstacionAlObtenerElVuelo() {
        Vuelo vuelo = vueloConId(1L, "PU302", fechaFutura);
        when(vueloRepository.findById(1L)).thenReturn(Optional.of(vuelo));
        when(vueloRepository.existsByCodigoVueloAndFechaVueloAndIdNot("PU302", fechaFutura, 1L))
                .thenReturn(false);
        when(vueloRepository.save(any())).thenReturn(vuelo);

        vueloService.update(1L, req("PU302", "LIM", "BOG", fechaFutura));

        // getOrThrow() debe pasar el registro por EstacionContext.validarAccesoLectura
        // antes de devolverlo — así el Líder de otra estación nunca llega a leerlo.
        verify(estacionContext).validarAccesoLectura(vuelo);
    }

    @Test
    void update_vueloDeOtraEstacion_propagaAccesoDenegado() {
        Vuelo vuelo = vueloConId(1L, "PU302", fechaFutura);
        when(vueloRepository.findById(1L)).thenReturn(Optional.of(vuelo));
        doThrow(new AccesoDenegadoException("No tiene acceso a este recurso: pertenece a otra estación"))
                .when(estacionContext).validarAccesoLectura(vuelo);

        assertThrows(AccesoDenegadoException.class,
                () -> vueloService.update(1L, req("PU302", "LIM", "BOG", fechaFutura)));
        verify(vueloRepository, never()).save(any());
    }

    @Test
    void create_usuarioConVariasEstacionesOLineasSinContextoEnRequest_propagaBadRequest() {
        // Replica lo que hace el EstacionContext real cuando el usuario tiene
        // 2+ estaciones/líneas (o es Administrador Global) y no especificó
        // estacionId/lineaAereaId (contexto de trabajo activo).
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(lider));
        when(estacionContext.resolverContextoActivo(null, null))
                .thenThrow(new BadRequestException(
                        "Debe especificar estacionId y lineaAereaId (contexto de trabajo activo)"));

        VueloRequest req = new VueloRequest("PlusUltra", "PU303", "LIM", "BOG",
                fechaFutura, ContingenciaEnum.CANCELACION, "", null, null);
        assertThrows(BadRequestException.class, () -> vueloService.create(req, 1L));
        verify(vueloRepository, never()).save(any());
    }

    @Test
    void create_estacionSolicitadaFueraDeAlcanceDelUsuario_propagaAccesoDenegado() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(lider));
        when(estacionContext.resolverContextoActivo(99L, null))
                .thenThrow(new AccesoDenegadoException("No tiene acceso a la estación 99"));

        VueloRequest req = new VueloRequest("PlusUltra", "PU304", "LIM", "BOG",
                fechaFutura, ContingenciaEnum.CANCELACION, "", 99L, null);
        assertThrows(AccesoDenegadoException.class, () -> vueloService.create(req, 1L));
        verify(vueloRepository, never()).save(any());
    }

    @Test
    void create_aerolineaNoCoincideConContextoActivo_lanzaBadRequest() {
        // La línea aérea real del registro la determina SIEMPRE el contexto
        // de trabajo activo (topbar), nunca el texto libre 'aerolinea' — si
        // no coinciden, es un error de tipeo o de selector desincronizado.
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(lider));

        VueloRequest req = new VueloRequest("Iberia", "PU305", "LIM", "BOG",
                fechaFutura, ContingenciaEnum.CANCELACION, "", null, null);
        assertThrows(BadRequestException.class, () -> vueloService.create(req, 1L));
        verify(vueloRepository, never()).save(any());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Builders de utilidad
    // ════════════════════════════════════════════════════════════════════════

    private VueloRequest req(String codigo, String origen, String destino, LocalDate fecha) {
        return new VueloRequest("PlusUltra", codigo, origen, destino,
                fecha, ContingenciaEnum.CANCELACION, "Test", null, null);
    }

    private Vuelo vueloConId(Long id, String codigo, LocalDate fecha) {
        return Vuelo.builder()
                .id(id).aerolinea("PlusUltra").codigoVuelo(codigo)
                .origen("LIM").destino("BOG").fechaVuelo(fecha)
                .tipoContingencia(ContingenciaEnum.CANCELACION)
                .estado(EstadoVueloEnum.ACTIVO).creadoPor(lider).build();
    }

    private Proveedor proveedorHotel(Long id, String nombre) {
        return Proveedor.builder().id(id).estado(1)
                .tipo(TipoProveedorEnum.HOTEL).nombre(nombre).build();
    }

    private Proveedor proveedorTransporte(Long id, String nombre) {
        return Proveedor.builder().id(id).estado(1)
                .tipo(TipoProveedorEnum.TRANSPORTE).nombre(nombre).build();
    }
}
