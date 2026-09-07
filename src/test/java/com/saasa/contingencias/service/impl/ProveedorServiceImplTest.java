package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.*;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.dto.request.ProveedorRequest;
import com.saasa.contingencias.domain.dto.response.ProveedorResponse;
import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import com.saasa.contingencias.domain.mapping.ProveedorMapper;
import com.saasa.contingencias.domain.model.EstacionLineaAerea;
import com.saasa.contingencias.domain.model.Proveedor;
import com.saasa.contingencias.domain.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProveedorServiceImplTest {

    @Mock ProveedorRepository proveedorRepository;
    @Mock ServicioProveedorRepository servicioProveedorRepository;
    @Mock EstacionLineaAereaRepository estacionLineaAereaRepository;
    @Mock EstacionContext estacionContext;
    @InjectMocks ProveedorServiceImpl proveedorService;
    @Mock
    ProveedorMapper proveedorMapper;

    @BeforeEach
    void setUpEstacion() {
        // Fase 3: create()/createConServicios() ahora validan que la línea
        // aérea esté habilitada en la estación — se deja "habilitada" por
        // defecto para no romper los tests que no son de Fase 3.
        lenient().when(estacionLineaAereaRepository.findByEstacionIdAndLineaAereaId(any(), any()))
                .thenReturn(Optional.of(EstacionLineaAerea.builder().estado(1).build()));

        // Migración estacionesActuales()/resolverEstacionParaEscritura() →
        // resolverContextoActivo(): contexto de trabajo activo (estación+línea)
        // por defecto para los tests que no son específicamente sobre la
        // resolución del contexto. resolverContextoActivo(...) = escritura
        // (create/update), resolverContextoActivoLectura(...) = lecturas
        // (findAll) — desde el fix de historial multi-estación son métodos
        // distintos, así que se stubean ambos por defecto.
        lenient().when(estacionContext.resolverContextoActivo(any(), any()))
                .thenReturn(new ScopeEstacionLinea(1L, 1L));
        lenient().when(estacionContext.resolverContextoActivo())
                .thenReturn(new ScopeEstacionLinea(1L, 1L));
        lenient().when(estacionContext.resolverContextoActivoLectura())
                .thenReturn(new ScopeEstacionLinea(1L, 1L));
    }

    @Test
    void create_rucDuplicado_lanzaBadRequest() {
        when(proveedorRepository.existsByRuc("12345678901")).thenReturn(true);
        ProveedorRequest req = new ProveedorRequest(TipoProveedorEnum.HOTEL, "Hotel Test", "12345678901", "Av. Lima", "999", "hotel@test.com", 1L, null);
        assertThrows(BadRequestException.class, () -> proveedorService.create(req));
    }

    @Test
    void create_exitoso_retornaProveedor() {
        when(proveedorRepository.existsByRuc("12345678901")).thenReturn(false);
        Proveedor saved = Proveedor.builder().id(1L).tipo(TipoProveedorEnum.HOTEL).nombre("Hotel Test").ruc("12345678901").estado(1).build();
        when(proveedorRepository.save(any())).thenReturn(saved);

        when(proveedorMapper.toResponse(saved)).thenReturn(
                new ProveedorResponse(1L, "HOTEL", "Hotel Test", "12345678901",
                        "Av. Lima", "999", "hotel@test.com", 1, null));

        ProveedorRequest req = new ProveedorRequest(TipoProveedorEnum.HOTEL, "Hotel Test", "12345678901", "Av. Lima", "999", "hotel@test.com", 1L, null);
        var resp = proveedorService.create(req);
        assertNotNull(resp);
        assertEquals("Hotel Test", resp.nombre());
    }

    @Test
    void desactivar_proveedorConServicios_conservaHistorial() {
        Proveedor p = Proveedor.builder().id(1L).estado(1).build();
        when(proveedorRepository.findById(1L)).thenReturn(Optional.of(p));
        when(proveedorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertDoesNotThrow(() -> proveedorService.changeEstado(1L, 0));
        assertEquals(0, p.getEstado());
    }

    @Test
    void update_proveedorNoEncontrado_lanzaRecursoNoEncontrado() {
        when(proveedorRepository.findById(99L)).thenReturn(Optional.empty());
        ProveedorRequest req = new ProveedorRequest(TipoProveedorEnum.HOTEL, "X", "00000000000", "X", "X", "x@x.com", 1L, null);
        assertThrows(RecursoNoEncontradoException.class, () -> proveedorService.update(99L, req));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Fase 4 — Filtro de estación: mismos gaps que en VueloServiceImplTest.
    // El filtrado real con DB queda en EstacionScopeSpecificationIntegrationTest;
    // aquí solo se verifica que ProveedorServiceImpl consulta y respeta
    // EstacionContext y la validación de línea aérea habilitada por estación (RN-802).
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void findAll_consultaEstacionesDelUsuarioYDelegaFiltroAlRepositorio() {
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(1L, 1L));
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        when(proveedorRepository.findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<Proveedor>>any(),
                eq(pageable)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        proveedorService.findAll(null, null, pageable);

        verify(estacionContext).resolverContextoActivoLectura();
        verify(proveedorRepository).findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<Proveedor>>any(),
                eq(pageable));
    }

    @Test
    void update_delegaValidacionDeAccesoDeEstacionAlObtenerElProveedor() {
        Proveedor p = Proveedor.builder().id(1L).ruc("11111111111").estado(1).build();
        when(proveedorRepository.findById(1L)).thenReturn(Optional.of(p));
        when(proveedorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ProveedorRequest req = new ProveedorRequest(TipoProveedorEnum.HOTEL, "Hotel Test", "11111111111", "X", "X", "x@x.com", 1L, null);

        proveedorService.update(1L, req);

        verify(estacionContext).validarAccesoLectura(p);
    }

    @Test
    void update_proveedorDeOtraEstacion_propagaAccesoDenegado() {
        Proveedor p = Proveedor.builder().id(1L).ruc("11111111111").estado(1).build();
        when(proveedorRepository.findById(1L)).thenReturn(Optional.of(p));
        doThrow(new AccesoDenegadoException("No tiene acceso a este recurso: pertenece a otra estación"))
                .when(estacionContext).validarAccesoLectura(p);
        ProveedorRequest req = new ProveedorRequest(TipoProveedorEnum.HOTEL, "Hotel Test", "11111111111", "X", "X", "x@x.com", 1L, null);

        assertThrows(AccesoDenegadoException.class, () -> proveedorService.update(1L, req));
        verify(proveedorRepository, never()).save(any());
    }

    @Test
    void create_lineaAereaNoHabilitadaEnLaEstacion_lanzaBadRequest() {
        // RN-802: a diferencia de Vuelo (que auto-habilita la línea aérea), en
        // Proveedor una línea aérea no habilitada en la estación es error del cliente.
        when(proveedorRepository.existsByRuc("12345678901")).thenReturn(false);
        when(estacionContext.resolverContextoActivo(1L, 7L)).thenReturn(new ScopeEstacionLinea(1L, 7L));
        when(estacionLineaAereaRepository.findByEstacionIdAndLineaAereaId(1L, 7L))
                .thenReturn(Optional.empty());
        ProveedorRequest req = new ProveedorRequest(TipoProveedorEnum.HOTEL, "Hotel Test", "12345678901", "X", "X", "hotel@test.com", 7L, 1L);

        assertThrows(BadRequestException.class, () -> proveedorService.create(req));
        verify(proveedorRepository, never()).save(any());
    }

    @Test
    void create_usuarioConVariasEstaciones_sinEstacionIdEnRequest_propagaBadRequest() {
        when(proveedorRepository.existsByRuc("12345678901")).thenReturn(false);
        when(estacionContext.resolverContextoActivo(null, 1L))
                .thenThrow(new BadRequestException(
                        "Debe especificar estacionId y lineaAereaId (contexto de trabajo activo): el usuario "
                                + "tiene acceso a más de una estación/línea, o es Administrador Global"));
        ProveedorRequest req = new ProveedorRequest(TipoProveedorEnum.HOTEL, "Hotel Test", "12345678901", "X", "X", "hotel@test.com", 1L, null);

        assertThrows(BadRequestException.class, () -> proveedorService.create(req));
        verify(proveedorRepository, never()).save(any());
    }
}

