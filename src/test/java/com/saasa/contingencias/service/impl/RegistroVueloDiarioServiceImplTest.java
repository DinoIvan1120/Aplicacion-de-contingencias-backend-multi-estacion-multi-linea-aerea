package com.saasa.contingencias.service.impl;


import com.saasa.contingencias.config.exception.AccesoDenegadoException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.dto.request.RegistroVueloDiarioRequest;
import com.saasa.contingencias.domain.dto.request.VueloRecursoRequest;
import com.saasa.contingencias.domain.dto.response.CapacidadComprometidaResponse;
import com.saasa.contingencias.domain.dto.response.RegistroVueloDiarioResponse;
import com.saasa.contingencias.domain.mapping.RegistroVueloDiarioMapper;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test unitario de RegistroVueloDiarioServiceImpl.
 *
 * Usa el mapper REAL (RegistroVueloDiarioMapper) en vez de un mock: no tiene
 * dependencias externas, así que mockearlo solo agregaría stubs frágiles sin
 * ganar nada. Todo lo demás (repositorios) sí se mockea.
 */
@ExtendWith(MockitoExtension.class)
class RegistroVueloDiarioServiceImplTest {

    @Mock RegistroVueloDiarioRepository registroRepository;
    @Mock VueloRepository vueloRepository;
    @Mock UsuarioRepository usuarioRepository;
    @Mock ProveedorRepository proveedorRepository;
    @Mock VueloRecursoRepository vueloRecursoRepository;
    @Mock EstacionContext estacionContext;

    private RegistroVueloDiarioServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RegistroVueloDiarioServiceImpl(
                registroRepository, vueloRepository, usuarioRepository,
                proveedorRepository, vueloRecursoRepository,
                new RegistroVueloDiarioMapper(), // mapper real, sin dependencias
                estacionContext);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers de construcción
    // ══════════════════════════════════════════════════════════════════════

    private Vuelo vuelo(Long id) {
        return Vuelo.builder().id(id).aerolinea("PlusUltra").codigoVuelo("PU301")
                .origen("LIM").destino("MAD").fechaVuelo(LocalDate.of(2026, 7, 20)).build();
    }

    private Usuario lider(Long id) {
        return Usuario.builder().id(id).nombre("Lider").apellido("SAASA")
                .correo("lider@test.com").build();
    }

    private Proveedor proveedor(Long id, String nombre, TipoProveedorEnum tipo) {
        return Proveedor.builder().id(id).nombre(nombre).tipo(tipo).ruc("20123456789").build();
    }

    private VueloRecurso recursoHotel(Long id, RegistroVueloDiario registro, Proveedor p,
                                      int simples, int dobles, int matrimoniales, int estado) {
        return VueloRecurso.builder().id(id).vuelo(registro.getVueloItinerario())
                .registroVueloDiario(registro).proveedor(p)
                .habitacionesSimples(simples).habitacionesDobles(dobles)
                .habitacionesMatrimoniales(matrimoniales).estado(estado).build();
    }

    private VueloRecurso recursoCapacidad(Long id, RegistroVueloDiario registro, Proveedor p,
                                          int capacidadTotal, int estado) {
        return VueloRecurso.builder().id(id).vuelo(registro.getVueloItinerario())
                .registroVueloDiario(registro).proveedor(p)
                .capacidadTotal(capacidadTotal).estado(estado).build();
    }

    private RegistroVueloDiario registro(Long id, Vuelo v, Usuario lider) {
        return RegistroVueloDiario.builder().id(id).vueloItinerario(v).registradoPor(lider)
                .fechaRegistro(LocalDate.of(2026, 7, 20)).registradoEn(LocalDateTime.now())
                .active(true).build();
    }

    // ══════════════════════════════════════════════════════════════════════
    // registrarVuelo
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("registrarVuelo — vuelo del itinerario no existe → RecursoNoEncontradoException")
    void registrarVuelo_vueloNoExiste_lanzaExcepcion() {
        when(vueloRepository.findById(1L)).thenReturn(Optional.empty());
        RegistroVueloDiarioRequest req = new RegistroVueloDiarioRequest(1L, LocalDate.now(), null, null);

        assertThrows(RecursoNoEncontradoException.class,
                () -> service.registrarVuelo(req, 1L));
        verify(registroRepository, never()).save(any());
    }

    @Test
    @DisplayName("registrarVuelo — líder no existe → RecursoNoEncontradoException")
    void registrarVuelo_liderNoExiste_lanzaExcepcion() {
        when(vueloRepository.findById(1L)).thenReturn(Optional.of(vuelo(1L)));
        when(usuarioRepository.findById(99L)).thenReturn(Optional.empty());
        RegistroVueloDiarioRequest req = new RegistroVueloDiarioRequest(1L, LocalDate.now(), null, null);

        assertThrows(RecursoNoEncontradoException.class,
                () -> service.registrarVuelo(req, 99L));
    }

    @Test
    @DisplayName("registrarVuelo — vuelo ya registrado en esa fecha → IllegalStateException")
    void registrarVuelo_duplicado_lanzaExcepcion() {
        Vuelo v = vuelo(1L);
        when(vueloRepository.findById(1L)).thenReturn(Optional.of(v));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(lider(1L)));
        when(registroRepository.existsByVueloItinerarioIdAndFechaRegistroAndActivoTrue(1L, LocalDate.of(2026, 7, 20)))
                .thenReturn(true);

        RegistroVueloDiarioRequest req = new RegistroVueloDiarioRequest(1L, LocalDate.of(2026, 7, 20), null, null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.registrarVuelo(req, 1L));
        assertTrue(ex.getMessage().contains("PU301"));
        verify(registroRepository, never()).save(any());
    }

    @Test
    @DisplayName("registrarVuelo — sin recursos, caso exitoso → guarda y retorna response")
    void registrarVuelo_sinRecursos_exitoso() {
        Vuelo v = vuelo(1L);
        Usuario l = lider(1L);
        RegistroVueloDiario registroGuardado = registro(10L, v, l);

        when(vueloRepository.findById(1L)).thenReturn(Optional.of(v));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(l));
        when(registroRepository.existsByVueloItinerarioIdAndFechaRegistroAndActivoTrue(anyLong(), any()))
                .thenReturn(false);
        when(registroRepository.save(any(RegistroVueloDiario.class))).thenReturn(registroGuardado);
        when(registroRepository.findById(10L)).thenReturn(Optional.of(registroGuardado));

        RegistroVueloDiarioRequest req = new RegistroVueloDiarioRequest(
                1L, LocalDate.of(2026, 7, 20), null, "Contingencia por mal clima");

        RegistroVueloDiarioResponse resp = service.registrarVuelo(req, 1L);

        assertNotNull(resp);
        assertEquals(10L, resp.id());
        assertTrue(resp.recursos().isEmpty());
        verify(proveedorRepository, never()).findById(any());
    }

    @Test
    @DisplayName("registrarVuelo — con recursos → habilita cada recurso con el proveedor correspondiente")
    void registrarVuelo_conRecursos_habilitaCadaUno() {
        Vuelo v = vuelo(1L);
        Usuario l = lider(1L);
        Proveedor pHotel = proveedor(5L, "Hotel Costa del Sol", TipoProveedorEnum.HOTEL);
        RegistroVueloDiario registroGuardado = registro(10L, v, l);

        when(vueloRepository.findById(1L)).thenReturn(Optional.of(v));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(l));
        when(registroRepository.existsByVueloItinerarioIdAndFechaRegistroAndActivoTrue(anyLong(), any()))
                .thenReturn(false);
        when(proveedorRepository.findById(5L)).thenReturn(Optional.of(pHotel));
        when(registroRepository.save(any(RegistroVueloDiario.class))).thenReturn(registroGuardado);
        when(registroRepository.findById(10L)).thenReturn(Optional.of(registroGuardado));

        VueloRecursoRequest recursoReq = new VueloRecursoRequest(5L, 10, 20, 4, null);
        RegistroVueloDiarioRequest req = new RegistroVueloDiarioRequest(
                1L, LocalDate.of(2026, 7, 20), List.of(recursoReq), null);

        RegistroVueloDiarioResponse resp = service.registrarVuelo(req, 1L);

        assertEquals(1, registroGuardado.getRecursos().size());
        assertEquals(pHotel, registroGuardado.getRecursos().get(0).getProveedor());
        assertEquals(1, resp.recursos().size());
    }

    @Test
    @DisplayName("registrarVuelo — recurso con proveedor inexistente → RecursoNoEncontradoException")
    void registrarVuelo_proveedorDeRecursoNoExiste_lanzaExcepcion() {
        Vuelo v = vuelo(1L);
        Usuario l = lider(1L);
        RegistroVueloDiario registroGuardado = registro(10L, v, l);

        when(vueloRepository.findById(1L)).thenReturn(Optional.of(v));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(l));
        when(registroRepository.existsByVueloItinerarioIdAndFechaRegistroAndActivoTrue(anyLong(), any()))
                .thenReturn(false);
        when(registroRepository.save(any(RegistroVueloDiario.class))).thenReturn(registroGuardado);
        when(proveedorRepository.findById(999L)).thenReturn(Optional.empty());

        VueloRecursoRequest recursoReq = new VueloRecursoRequest(999L, 1, 1, 1, null);
        RegistroVueloDiarioRequest req = new RegistroVueloDiarioRequest(
                1L, LocalDate.of(2026, 7, 20), List.of(recursoReq), null);

        assertThrows(RecursoNoEncontradoException.class, () -> service.registrarVuelo(req, 1L));
    }

    // ══════════════════════════════════════════════════════════════════════
    // obtenerPorId / obtenerRegistrosDelDia
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("obtenerPorId — registro no existe → RecursoNoEncontradoException")
    void obtenerPorId_noExiste_lanzaExcepcion() {
        when(registroRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(RecursoNoEncontradoException.class, () -> service.obtenerPorId(1L));
    }

    @Test
    @DisplayName("obtenerPorId — Fase 4: registro de otra estación → AccesoDenegadoException")
    void obtenerPorId_registroDeOtraEstacion_lanzaAccesoDenegado() {
        RegistroVueloDiario r = registro(10L, vuelo(1L), lider(1L));
        when(registroRepository.findById(10L)).thenReturn(Optional.of(r));
        doThrow(new AccesoDenegadoException("Fuera de su alcance"))
                .when(estacionContext).validarAccesoLectura(r);

        assertThrows(AccesoDenegadoException.class, () -> service.obtenerPorId(10L));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Fase 4 — filtrarPorEstacion(): filtro EN MEMORIA usado por los 4
    // métodos de listado (obtenerRegistrosDelDia/obtenerPorRangoFechas/
    // obtenerMisRegistros/obtenerTodos). A diferencia de Vuelo/Proveedor,
    // este repositorio no usa Specification (ver Parte 6 de la guía), así
    // que el filtro se prueba directamente sobre lo que devuelve el mock del
    // repositorio, no con H2. Ningún test cubría estos 4 métodos hasta ahora.
    // ══════════════════════════════════════════════════════════════════════

    private RegistroVueloDiario registroDeEstacion(Long id, Long estacionId, Long lineaAereaId) {
        RegistroVueloDiario r = registro(id, vuelo(1L), lider(1L));
        r.setEstacionId(estacionId);
        r.setLineaAereaId(lineaAereaId);
        return r;
    }

    @Test
    @DisplayName("obtenerRegistrosDelDia — usuario de una estación no ve registros de otra")
    void obtenerRegistrosDelDia_usuarioDeUnaEstacion_excluyeRegistrosDeOtraEstacion() {
        RegistroVueloDiario deLima = registroDeEstacion(1L, 10L, 100L);
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(10L, 100L));
        when(registroRepository.findByFechaRegistroEntreYActivoTrueList(any(), any(), eq(10L), eq(100L)))
                .thenReturn(List.of(deLima));

        var resultado = service.obtenerRegistrosDelDia();

        assertEquals(1, resultado.size());
        assertEquals(1L, resultado.get(0).id());
    }

    @Test
    @DisplayName("obtenerRegistrosDelDia — el contexto activo también excluye por línea aérea dentro de la misma estación")
    void obtenerRegistrosDelDia_contextoActivo_excluyeOtraLineaAereaEnLaMismaEstacion() {
        RegistroVueloDiario deLima = registroDeEstacion(1L, 10L, 100L);
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(10L, 100L));
        when(registroRepository.findByFechaRegistroEntreYActivoTrueList(any(), any(), eq(10L), eq(100L)))
                .thenReturn(List.of(deLima));

        var resultado = service.obtenerRegistrosDelDia();

        assertEquals(1, resultado.size());
        assertEquals(1L, resultado.get(0).id());
    }

    @Test
    @DisplayName("obtenerRegistrosDelDia — Administrador Global también queda acotado al contexto de trabajo elegido")
    void obtenerRegistrosDelDia_administradorGlobal_seAcotaAlContextoActivo() {
        // AHORA el contexto de trabajo (estación+línea) es obligatorio incluso
        // para el Administrador Global — ya no existe un "ve todo" implícito.
        RegistroVueloDiario deLima = registroDeEstacion(1L, 10L, 100L);
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(10L, 100L));
        when(registroRepository.findByFechaRegistroEntreYActivoTrueList(any(), any(), eq(10L), eq(100L)))
                .thenReturn(List.of(deLima));

        var resultado = service.obtenerRegistrosDelDia();

        assertEquals(1, resultado.size());
        assertEquals(1L, resultado.get(0).id());
    }

    @Test
    @DisplayName("obtenerRegistrosDelDia — REGRESIÓN bug historial: 'sin filtrar por línea' (lineaAereaId null) muestra TODAS las líneas de la estación, no 400/vacío")
    void obtenerRegistrosDelDia_sinLineaAereaEnElContexto_muestraTodasLasLineasDeLaEstacion() {
        // Reproduce exactamente el escenario reportado: el usuario eligió
        // "Continuar sin filtrar por línea" en el selector de estación, así
        // que el contexto activo llega con lineaAereaId == null. Antes del
        // fix, resolverContextoActivo() explotaba con BadRequestException
        // en este caso (multi-estación / admin sin línea fija), y el
        // historial se veía vacío en la vista del líder.
        RegistroVueloDiario deLimaPlusUltra = registroDeEstacion(1L, 10L, 100L);
        RegistroVueloDiario deLimaLatam = registroDeEstacion(2L, 10L, 200L);
        // estación = Lima (10), línea = null → "todas las líneas de Lima"
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(10L, null));
        when(registroRepository.findByFechaRegistroEntreYActivoTrueList(any(), any(), eq(10L), isNull()))
                .thenReturn(List.of(deLimaPlusUltra, deLimaLatam));

        var resultado = service.obtenerRegistrosDelDia();

        // Debe ver AMBOS registros de Lima (Plus Ultra y Latam).
        assertEquals(2, resultado.size());
        assertTrue(resultado.stream().anyMatch(r -> r.id().equals(1L)));
        assertTrue(resultado.stream().anyMatch(r -> r.id().equals(2L)));
    }

    @Test
    @DisplayName("obtenerTodos — el filtro va en el WHERE: el total de la página refleja lo que realmente cumple la condición")
    void obtenerTodos_usuarioDeUnaEstacion_totalDePaginaCorrecto() {
        RegistroVueloDiario deLima = registroDeEstacion(1L, 10L, 100L);
        Pageable pageable = PageRequest.of(0, 10);
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(10L, 100L));
        when(registroRepository.findAllByActivoTrue(eq(10L), eq(100L), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(deLima), pageable, 1));

        var resultado = service.obtenerTodos(pageable);

        assertEquals(1, resultado.getTotalElements());
        assertEquals(1L, resultado.getContent().get(0).id());
    }

    // ══════════════════════════════════════════════════════════════════════
    // actualizarRecursos — sincronización tipo upsert
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("actualizarRecursos — registro no encontrado → RecursoNoEncontradoException")
    void actualizarRecursos_registroNoExiste_lanzaExcepcion() {
        when(registroRepository.findById(1L)).thenReturn(Optional.empty());
        RegistroVueloDiarioRequest req = new RegistroVueloDiarioRequest(1L, LocalDate.now(), null, null);

        assertThrows(RecursoNoEncontradoException.class,
                () -> service.actualizarRecursos(1L, req, 1L));
    }

    @Test
    @DisplayName("actualizarRecursos — Fase 4: registro de otra estación → AccesoDenegadoException, no guarda")
    void actualizarRecursos_registroDeOtraEstacion_lanzaAccesoDenegadoYNoGuarda() {
        RegistroVueloDiario r = registro(10L, vuelo(1L), lider(1L));
        when(registroRepository.findById(10L)).thenReturn(Optional.of(r));
        doThrow(new AccesoDenegadoException("Fuera de su alcance"))
                .when(estacionContext).validarAccesoLectura(r);
        RegistroVueloDiarioRequest req = new RegistroVueloDiarioRequest(1L, LocalDate.now(), null, null);

        assertThrows(AccesoDenegadoException.class,
                () -> service.actualizarRecursos(10L, req, 1L));
        verify(registroRepository, never()).save(any());
    }

    @Test
    @DisplayName("actualizarRecursos — proveedor ya no está en el request → se desactiva (estado=0)")
    void actualizarRecursos_proveedorFueraDelRequest_seDesactiva() {
        Vuelo v = vuelo(1L);
        Usuario l = lider(1L);
        Proveedor pHotelViejo = proveedor(5L, "Hotel Viejo", TipoProveedorEnum.HOTEL);
        RegistroVueloDiario reg = registro(10L, v, l);
        VueloRecurso recursoViejo = recursoHotel(20L, reg, pHotelViejo, 5, 5, 0, 1);
        reg.agregarRecurso(recursoViejo);

        when(registroRepository.findById(10L)).thenReturn(Optional.of(reg));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(l));
        when(registroRepository.save(any(RegistroVueloDiario.class))).thenReturn(reg);

        // El request ya NO incluye al proveedor 5L
        RegistroVueloDiarioRequest req = new RegistroVueloDiarioRequest(
                1L, LocalDate.of(2026, 7, 20), List.of(), null);

        service.actualizarRecursos(10L, req, 1L);

        assertEquals(0, recursoViejo.getEstado());
    }

    @Test
    @DisplayName("actualizarRecursos — proveedor existente reenviado → se actualiza y reactiva (estado=1)")
    void actualizarRecursos_proveedorExistenteReenviado_seActualizaYReactiva() {
        Vuelo v = vuelo(1L);
        Usuario l = lider(1L);
        Proveedor pHotel = proveedor(5L, "Hotel Costa del Sol", TipoProveedorEnum.HOTEL);
        RegistroVueloDiario reg = registro(10L, v, l);
        VueloRecurso recursoExistente = recursoHotel(20L, reg, pHotel, 5, 5, 0, 0); // estaba desactivado
        reg.agregarRecurso(recursoExistente);

        when(registroRepository.findById(10L)).thenReturn(Optional.of(reg));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(l));
        when(registroRepository.save(any(RegistroVueloDiario.class))).thenReturn(reg);

        VueloRecursoRequest recursoReq = new VueloRecursoRequest(5L, 8, 12, 2, null);
        RegistroVueloDiarioRequest req = new RegistroVueloDiarioRequest(
                1L, LocalDate.of(2026, 7, 20), List.of(recursoReq), null);

        service.actualizarRecursos(10L, req, 1L);

        assertEquals(1, recursoExistente.getEstado()); // reactivado
        assertEquals(8, recursoExistente.getHabitacionesSimples());
        assertEquals(12, recursoExistente.getHabitacionesDobles());
        verify(proveedorRepository, never()).findById(any()); // no crea uno nuevo
    }

    @Test
    @DisplayName("actualizarRecursos — proveedor nuevo en el request → se crea y agrega al registro")
    void actualizarRecursos_proveedorNuevo_seCrea() {
        Vuelo v = vuelo(1L);
        Usuario l = lider(1L);
        Proveedor pNuevo = proveedor(7L, "Hotel Nuevo", TipoProveedorEnum.HOTEL);
        RegistroVueloDiario reg = registro(10L, v, l); // sin recursos previos

        when(registroRepository.findById(10L)).thenReturn(Optional.of(reg));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(l));
        when(proveedorRepository.findById(7L)).thenReturn(Optional.of(pNuevo));
        when(registroRepository.save(any(RegistroVueloDiario.class))).thenReturn(reg);

        VueloRecursoRequest recursoReq = new VueloRecursoRequest(7L, 3, 6, 1, null);
        RegistroVueloDiarioRequest req = new RegistroVueloDiarioRequest(
                1L, LocalDate.of(2026, 7, 20), List.of(recursoReq), null);

        service.actualizarRecursos(10L, req, 1L);

        assertEquals(1, reg.getRecursos().size());
        assertEquals(pNuevo, reg.getRecursos().get(0).getProveedor());
    }

    @Test
    @DisplayName("actualizarRecursos — proveedor nuevo inexistente → RecursoNoEncontradoException")
    void actualizarRecursos_proveedorNuevoInexistente_lanzaExcepcion() {
        Vuelo v = vuelo(1L);
        Usuario l = lider(1L);
        RegistroVueloDiario reg = registro(10L, v, l);

        when(registroRepository.findById(10L)).thenReturn(Optional.of(reg));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(l));
        when(proveedorRepository.findById(999L)).thenReturn(Optional.empty());

        VueloRecursoRequest recursoReq = new VueloRecursoRequest(999L, 1, 1, 1, null);
        RegistroVueloDiarioRequest req = new RegistroVueloDiarioRequest(
                1L, LocalDate.of(2026, 7, 20), List.of(recursoReq), null);

        assertThrows(RecursoNoEncontradoException.class,
                () -> service.actualizarRecursos(10L, req, 1L));
    }

    @Test
    @DisplayName("actualizarRecursos — observaciones se actualizan cuando vienen en el request")
    void actualizarRecursos_actualizaObservaciones() {
        Vuelo v = vuelo(1L);
        Usuario l = lider(1L);
        RegistroVueloDiario reg = registro(10L, v, l);

        when(registroRepository.findById(10L)).thenReturn(Optional.of(reg));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(l));
        when(registroRepository.save(any(RegistroVueloDiario.class))).thenReturn(reg);

        RegistroVueloDiarioRequest req = new RegistroVueloDiarioRequest(
                1L, LocalDate.of(2026, 7, 20), null, "Nueva observación");

        service.actualizarRecursos(10L, req, 1L);

        assertEquals("Nueva observación", reg.getObservaciones());
    }

    // ══════════════════════════════════════════════════════════════════════
    // eliminarRegistro — soft delete
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("eliminarRegistro — marca el registro inactivo y desactiva todos sus recursos")
    void eliminarRegistro_marcaInactivoYDesactivaRecursos() {
        Vuelo v = vuelo(1L);
        Usuario l = lider(1L);
        Proveedor p = proveedor(5L, "Hotel Costa del Sol", TipoProveedorEnum.HOTEL);
        RegistroVueloDiario reg = registro(10L, v, l);
        VueloRecurso r1 = recursoHotel(20L, reg, p, 5, 5, 0, 1);
        VueloRecurso r2 = recursoHotel(21L, reg, p, 3, 3, 0, 1);
        reg.agregarRecurso(r1);
        reg.agregarRecurso(r2);

        when(registroRepository.findById(10L)).thenReturn(Optional.of(reg));
        when(registroRepository.save(any(RegistroVueloDiario.class))).thenReturn(reg);

        service.eliminarRegistro(10L, 1L);

        assertFalse(reg.getActive());
        assertEquals(0, r1.getEstado());
        assertEquals(0, r2.getEstado());
    }

    @Test
    @DisplayName("eliminarRegistro — registro no existe → RecursoNoEncontradoException")
    void eliminarRegistro_noExiste_lanzaExcepcion() {
        when(registroRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(RecursoNoEncontradoException.class, () -> service.eliminarRegistro(1L, 1L));
    }

    @Test
    @DisplayName("eliminarRegistro — Fase 4: registro de otra estación → AccesoDenegadoException, no guarda")
    void eliminarRegistro_registroDeOtraEstacion_lanzaAccesoDenegadoYNoGuarda() {
        RegistroVueloDiario r = registro(10L, vuelo(1L), lider(1L));
        when(registroRepository.findById(10L)).thenReturn(Optional.of(r));
        doThrow(new AccesoDenegadoException("Fuera de su alcance"))
                .when(estacionContext).validarAccesoLectura(r);

        assertThrows(AccesoDenegadoException.class, () -> service.eliminarRegistro(10L, 1L));
        verify(registroRepository, never()).save(any());
    }

    // ══════════════════════════════════════════════════════════════════════
    // existeRegistro
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("existeRegistro — delega directamente al repositorio")
    void existeRegistro_delegaAlRepositorio() {
        when(registroRepository.existsByVueloItinerarioIdAndFechaRegistroAndActivoTrue(1L, LocalDate.of(2026, 7, 20)))
                .thenReturn(true);

        assertTrue(service.existeRegistro(1L, LocalDate.of(2026, 7, 20)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // obtenerCapacidadComprometidaHoy — agregación por proveedor
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("capacidadComprometidaHoy — HOTEL: primera aparición de un proveedor")
    void capacidadComprometida_hotelPrimeraAparicion() {
        Vuelo v = vuelo(1L);
        Usuario l = lider(1L);
        Proveedor pHotel = proveedor(5L, "Hotel Costa del Sol", TipoProveedorEnum.HOTEL);
        RegistroVueloDiario reg = registro(10L, v, l);
        VueloRecurso r = recursoHotel(20L, reg, pHotel, 10, 20, 4, 1);

        when(vueloRecursoRepository.findActivosPorRangoFechaExcluyendoRegistro(any(), any(), any()))
                .thenReturn(List.of(r));

        Map<Long, CapacidadComprometidaResponse> mapa = service.obtenerCapacidadComprometidaHoy(null);

        CapacidadComprometidaResponse resp = mapa.get(5L);
        assertNotNull(resp);
        assertEquals(10, resp.simplesComprometidos());
        assertEquals(20, resp.doblesComprometidos());
        assertEquals(4, resp.matrimonialesComprometidos());
        assertNull(resp.capacidadTotalComprometida());
    }

    @Test
    @DisplayName("capacidadComprometidaHoy — HOTEL: mismo proveedor en 2 recursos → se acumula, no se sobrescribe")
    void capacidadComprometida_hotelAcumulaSobreElMismoProveedor() {
        Vuelo v1 = vuelo(1L);
        Vuelo v2 = vuelo(2L);
        Usuario l = lider(1L);
        Proveedor pHotel = proveedor(5L, "Hotel Costa del Sol", TipoProveedorEnum.HOTEL);
        RegistroVueloDiario reg1 = registro(10L, v1, l);
        RegistroVueloDiario reg2 = registro(11L, v2, l);
        VueloRecurso r1 = recursoHotel(20L, reg1, pHotel, 10, 20, 4, 1);
        VueloRecurso r2 = recursoHotel(21L, reg2, pHotel, 5, 5, 1, 1); // mismo proveedor, otro vuelo/registro

        when(vueloRecursoRepository.findActivosPorRangoFechaExcluyendoRegistro(any(), any(), any()))
                .thenReturn(List.of(r1, r2));

        Map<Long, CapacidadComprometidaResponse> mapa = service.obtenerCapacidadComprometidaHoy(null);

        CapacidadComprometidaResponse resp = mapa.get(5L);
        assertEquals(15, resp.simplesComprometidos());   // 10 + 5
        assertEquals(25, resp.doblesComprometidos());    // 20 + 5
        assertEquals(5, resp.matrimonialesComprometidos()); // 4 + 1
        assertEquals(1, mapa.size()); // un solo proveedor en el mapa, no dos entradas
    }

    @Test
    @DisplayName("capacidadComprometidaHoy — TRANSPORTE: usa capacidadTotal, no habitaciones")
    void capacidadComprometida_transporteUsaCapacidadTotal() {
        Vuelo v = vuelo(1L);
        Usuario l = lider(1L);
        Proveedor pTrans = proveedor(6L, "Taxi Express", TipoProveedorEnum.TRANSPORTE);
        RegistroVueloDiario reg = registro(10L, v, l);
        VueloRecurso r = recursoCapacidad(22L, reg, pTrans, 15, 1);

        when(vueloRecursoRepository.findActivosPorRangoFechaExcluyendoRegistro(any(), any(), any()))
                .thenReturn(List.of(r));

        Map<Long, CapacidadComprometidaResponse> mapa = service.obtenerCapacidadComprometidaHoy(null);

        CapacidadComprometidaResponse resp = mapa.get(6L);
        assertEquals(15, resp.capacidadTotalComprometida());
        assertNull(resp.simplesComprometidos());
    }

    @Test
    @DisplayName("capacidadComprometidaHoy — sin recursos activos hoy → mapa vacío")
    void capacidadComprometida_sinRecursos_mapaVacio() {
        when(vueloRecursoRepository.findActivosPorRangoFechaExcluyendoRegistro(any(), any(), any()))
                .thenReturn(List.of());

        Map<Long, CapacidadComprometidaResponse> mapa = service.obtenerCapacidadComprometidaHoy(null);

        assertTrue(mapa.isEmpty());
    }
}
