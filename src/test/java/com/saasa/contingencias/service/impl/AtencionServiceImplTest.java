package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.*;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.domain.dto.request.AtencionRequest;
import com.saasa.contingencias.domain.dto.request.ServicioAsignadoRequest;
import com.saasa.contingencias.domain.enumeration.*;
import com.saasa.contingencias.domain.mapping.AtencionMapper;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.service.*;
import com.saasa.contingencias.service.ICorrelativoService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AtencionServiceImplTest {

    @Mock AtencionRepository atencionRepository;
    @Mock ServicioAsignadoRepository servicioAsignadoRepository;
    @Mock VueloRepository vueloRepository;
    @Mock VueloRecursoRepository vueloRecursoRepository;
    @Mock ServicioProveedorRepository servicioProveedorRepository;
    @Mock UsuarioRepository usuarioRepository;
    @Mock ProveedorRepository proveedorRepository;
    @Mock IAuditoriaService auditoriaService;
    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock IDisponibilidadService disponibilidadService;
    @Mock RegistroVueloDiarioRepository registroVueloDiarioRepository;
    @Mock ICorrelativoService correlativoService;
    @Mock IServicioMontoService servicioMontoService;
    @Mock
    private AtencionMapper atencionMapper;
    @Mock EstacionContext estacionContext;

    @InjectMocks AtencionServiceImpl atencionService;

    private Vuelo vueloActivo;
    private Usuario agente;

    private RegistroVueloDiario registroDiario;

    // ── Helpers para construir AtencionRequest sin repetir los 9 campos ──

    private AtencionRequest reqValido(String pnr, long vueloId) {
        return new AtencionRequest(
                "Juan", "Perez",
                pnr,
                "jp@test.com",
                null,
                vueloId,
                1L,           // registroVueloDiarioId
                null,         // codigoBarras
                null,         // fechaEmision
                null,
                null,// lugarEmision
                null
        );
    }

    private AtencionRequest reqValidoConBoardingPass(String pnr, long vueloId) {
        return new AtencionRequest(
                "Juan", "Perez",
                pnr,
                "jp@test.com",
                null,
                vueloId,
                1L,
                "BP-ABC123456",
                LocalDate.of(2025, 1, 15),
                "Lima",
                null,
                null
        );
    }

    @BeforeEach
    void setUp() {
        vueloActivo = Vuelo.builder()
                .id(1L)
                .codigoVuelo("PU302")
                .estado(EstadoVueloEnum.ACTIVO)
                .build();
        vueloActivo.setEstacionId(100L);
        vueloActivo.setLineaAereaId(200L);
        agente = Usuario.builder()
                .id(1L)
                .nombre("Juan")
                .apellido("Perez")
                .correo("agente@saasa.com")
                .build();

        // ← nuevo
        registroDiario = RegistroVueloDiario.builder()
                .id(1L)
                .active(true)
                .vueloItinerario(vueloActivo)
                .build();
    }

    @Test
    void create_conPnrInvalido_lanzaBadRequestException() {
        AtencionRequest req = new AtencionRequest(
                "Juan", "Perez",
                "INVALID!",
                "jp@test.com",
                null,        // telefono
                1L,
                1L,
                null, null, null,null,null
        );
        assertThrows(BadRequestException.class, () -> atencionService.create(req, 1L));
    }

    @Test
    void create_conPnrDuplicadoActivo_permiteRegistrarNuevaAtencion() {
        AtencionRequest req = reqValido("ABC123", 1L);

        when(registroVueloDiarioRepository.findById(1L))
                .thenReturn(Optional.of(registroDiario));
        when(vueloRepository.findById(1L)).thenReturn(Optional.of(vueloActivo));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agente));
        when(correlativoService.generarCorrelativo(100L, 200L)).thenReturn("SGC-LIM-PU-001998");


        Atencion saved = Atencion.builder()
                .id(21L)
                .numeroCorrelativo("SGC-LIM-PU-001998")
                .vuelo(vueloActivo)
                .nombre("Juan").apellido("Perez")
                .pnr("ABC123").correo("jp@test.com")
                .estado(EstadoAtencionEnum.ACTIVO)
                .atendidoPor(agente)
                .build();
        when(atencionRepository.save(any())).thenReturn(saved);

        var expected = new com.saasa.contingencias.domain.dto.response.AtencionResponse(
                21L, "SGC-LIM-PU-001998", 1L, "PU302", "Juan", "Perez", "ABC123", "jp@test.com",
                java.math.BigDecimal.ZERO, null, null, "ACTIVO", "Juan Perez", null,null,null,null,null,null);
        when(atencionMapper.toResponse(saved)).thenReturn(expected);

        var response = atencionService.create(req, 1L);

        assertNotNull(response);
        assertEquals("ABC123", response.pnr());
    }

    @Test
    void create_vueloAnulado_lanzaBadRequestException() {
        Vuelo vueloAnulado = Vuelo.builder()
                .id(2L)
                .estado(EstadoVueloEnum.ANULADO)
                .build();
        AtencionRequest req = reqValido("ABC123", 2L);
        when(vueloRepository.findById(2L)).thenReturn(Optional.of(vueloAnulado));
        assertThrows(BadRequestException.class, () -> atencionService.create(req, 1L));
    }

    @Test
    void create_exitoso_retornaAtencionResponse() {
        AtencionRequest req = reqValido("ABC123", 1L);

        when(registroVueloDiarioRepository.findById(1L))
                .thenReturn(Optional.of(registroDiario));
        when(vueloRepository.findById(1L)).thenReturn(Optional.of(vueloActivo));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agente));
        when(correlativoService.generarCorrelativo(100L, 200L)).thenReturn("SGC-LIM-PU-001000");

        Atencion saved = Atencion.builder()
                .id(10L)
                .numeroCorrelativo("SGC-LIM-PU-001000")
                .vuelo(vueloActivo)
                .nombre("Juan").apellido("Perez")
                .pnr("ABC123").correo("jp@test.com")
                .estado(EstadoAtencionEnum.ACTIVO)
                .atendidoPor(agente)
                .build();
        when(atencionRepository.save(any())).thenReturn(saved);

        var expected = new com.saasa.contingencias.domain.dto.response.AtencionResponse(
                10L, "SGC-LIM-PU-001000", 1L, "PU302", "Juan", "Perez", "ABC123", "jp@test.com",
                java.math.BigDecimal.ZERO, null, null, "ACTIVO", "Juan Perez", null,null,null,null,null,null);
        when(atencionMapper.toResponse(saved)).thenReturn(expected);

        var response = atencionService.create(req, 1L);
        assertNotNull(response);
        assertEquals("ABC123", response.pnr());
        assertEquals("SGC-LIM-PU-001000", response.numeroCorrelativo());
    }

    @Test
    void create_exitoso_conBoardingPass_retornaAtencionResponse() {
        AtencionRequest req = reqValidoConBoardingPass("ABC123", 1L);

        when(registroVueloDiarioRepository.findById(1L))
                .thenReturn(Optional.of(registroDiario));
        when(vueloRepository.findById(1L)).thenReturn(Optional.of(vueloActivo));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agente));
        when(correlativoService.generarCorrelativo(100L, 200L)).thenReturn("SGC-LIM-PU-001001");

        Atencion saved = Atencion.builder()
                .id(11L)
                .numeroCorrelativo("SGC-LIM-PU-001001")
                .vuelo(vueloActivo)
                .nombre("Juan").apellido("Perez")
                .pnr("ABC123").correo("jp@test.com")
                .estado(EstadoAtencionEnum.ACTIVO)
                .atendidoPor(agente)
                .build();
        when(atencionRepository.save(any())).thenReturn(saved);

        var expected = new com.saasa.contingencias.domain.dto.response.AtencionResponse(
                11L, "SGC-LIM-PU-001001", 1L, "PU302", "Juan", "Perez", "ABC123", "jp@test.com",
                java.math.BigDecimal.ZERO, null, null, "ACTIVO", "Juan Perez", null,null,null,null,null,null);
        when(atencionMapper.toResponse(saved)).thenReturn(expected);


        var response = atencionService.create(req, 1L);
        assertNotNull(response);
        assertEquals("ABC123", response.pnr());
        assertEquals("SGC-LIM-PU-001001", response.numeroCorrelativo());
    }

    @Test
    void anular_vueloConAtenciones_lanzaBadRequestException() {

        when(atencionRepository.existsByVueloId(1L)).thenReturn(true);
        assertThrows(BadRequestException.class, () -> {
            if (atencionRepository.existsByVueloId(1L))
                throw new BadRequestException("No se puede anular vuelo con atenciones");
        });
    }

    @Test
    void anular_sinAtenciones_noLanzaExcepcion() {
        when(atencionRepository.existsByVueloId(99L)).thenReturn(false);
        assertFalse(atencionRepository.existsByVueloId(99L));
    }

    @Test
    void anular_atencionExistente_cambiaEstadoAAnulado() {
        // Arrange
        Atencion atencion = Atencion.builder()
                .id(5L)
                .numeroCorrelativo("SGC-000000005")
                .estado(EstadoAtencionEnum.ACTIVO)
                .atendidoPor(agente)
                .vuelo(vueloActivo)
                .build();
        when(atencionRepository.findById(5L)).thenReturn(Optional.of(atencion));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agente));
        when(atencionRepository.save(any())).thenReturn(atencion);

        // Act
        atencionService.anular(5L, 1L);

        // Assert
        assertEquals(EstadoAtencionEnum.ANULADO, atencion.getEstado());
        verify(atencionRepository).save(atencion);
    }

    @Test
    void anular_atencionNoExistente_lanzaRecursoNoEncontradoException() {
        // Arrange
        when(atencionRepository.findById(99L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(RecursoNoEncontradoException.class,
                () -> atencionService.anular(99L, 1L));
        verify(atencionRepository, never()).save(any());
    }

// ══════════════════════════════════════════════════════════════════════════════
// verificarPnr()
// ══════════════════════════════════════════════════════════════════════════════

    @Test
    void verificarPnr_pnrExistente_retornaDuplicadoTrue() {
        // Arrange
        Atencion atencionExistente = Atencion.builder()
                .id(3L)
                .numeroCorrelativo("SGC-000000003")
                .nombre("Maria")
                .apellido("Lopez")
                .pnr("XYZ789")
                .build();
        when(atencionRepository.findByPnrAndVueloId("XYZ789", 1L))
                .thenReturn(Optional.of(atencionExistente));

        // Act
        var resultado = atencionService.verificarPnr("XYZ789", 1L);

        // Assert
        assertTrue(resultado.duplicado());
        assertEquals("XYZ789", resultado.pnr());
        assertEquals("Lopez/Maria", resultado.nombrePasajero());
        assertEquals("SGC-000000003", resultado.correlativo());
    }

    @Test
    void verificarPnr_pnrNuevo_retornaDuplicadoFalse() {
        // Arrange
        when(atencionRepository.findByPnrAndVueloId("NUEVO1", 1L))
                .thenReturn(Optional.empty());

        // Act
        var resultado = atencionService.verificarPnr("NUEVO1", 1L);

        // Assert
        assertFalse(resultado.duplicado());
        assertEquals("NUEVO1", resultado.pnr());
        assertNull(resultado.nombrePasajero());
        assertNull(resultado.correlativo());
    }

// ══════════════════════════════════════════════════════════════════════════════
// asignarServicios()
// ══════════════════════════════════════════════════════════════════════════════

    @Test
    void asignarServicios_atencionSinRegistroDiario_lanzaBadRequestException() {
        // Arrange — atención sin registroVueloDiario
        Atencion atencionSinRegistro = Atencion.builder()
                .id(7L)
                .estado(EstadoAtencionEnum.ACTIVO)
                .registroVueloDiario(null)  // ← sin registro diario
                .montoTotal(java.math.BigDecimal.ZERO)
                .build();
        when(atencionRepository.findById(7L)).thenReturn(Optional.of(atencionSinRegistro));

        // Act & Assert
        assertThrows(BadRequestException.class,
                () -> atencionService.asignarServicios(7L, List.of(), 1L));
    }

    @Test
    void asignarServicios_recursoInactivo_lanzaBadRequestException() {
        // Arrange
        com.saasa.contingencias.domain.model.RegistroVueloDiario registroDiario =
                com.saasa.contingencias.domain.model.RegistroVueloDiario.builder()
                        .id(10L)
                        .active(true)
                        .build();

        Atencion atencion = Atencion.builder()
                .id(8L)
                .estado(EstadoAtencionEnum.ACTIVO)
                .registroVueloDiario(registroDiario)
                .montoTotal(java.math.BigDecimal.ZERO)
                .build();

        Proveedor proveedor = Proveedor.builder()
                .id(1L)
                .nombre("Hotel Test")
                .build();

        VueloRecurso recursoInactivo = VueloRecurso.builder()
                .id(20L)
                .registroVueloDiario(registroDiario)
                .proveedor(proveedor)
                .estado(0)  // ← inactivo
                .build();

        com.saasa.contingencias.domain.dto.request.ServicioAsignadoRequest req =
                new com.saasa.contingencias.domain.dto.request.ServicioAsignadoRequest(
                        20L,                    // vueloRecursoId
                        TipoDetalleEnum.HOTEL,  // tipoDetalle
                        "SIMPLE",               // tipoHabitacion
                        false,                  // desayuno
                        false,                  // almuerzo
                        false,                  // cena
                        false,                  // snack
                        null,                   // fechaIngreso
                        null,                   // fechaSalida
                        null,                   // tipoTransporte
                        1                       // cantidad
                );

        when(atencionRepository.findById(8L)).thenReturn(Optional.of(atencion));
        when(vueloRecursoRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(recursoInactivo));

        // Act & Assert
        assertThrows(BadRequestException.class,
                () -> atencionService.asignarServicios(8L, List.of(req), 1L));
    }

    @Test
    @DisplayName("asignarServicios — TRANSPORTE con varios pasajeros → precio FIJO, NO se multiplica por cantidad")
    void asignarServicios_transporteVariosPasajeros_noMultiplicaPrecioPorCantidad() {
        // Arrange: proveedor de transporte cuyo precio "AMBOS" (S/65 + S/150) ya
        // viene calculado como 215.00 desde ServicioMontoService.
        RegistroVueloDiario registroDiario = RegistroVueloDiario.builder()
                .id(10L).active(true).build();

        Atencion atencion = Atencion.builder()
                .id(9L)
                .estado(EstadoAtencionEnum.ACTIVO)
                .registroVueloDiario(registroDiario)
                .montoTotal(BigDecimal.ZERO)
                .build();

        Proveedor proveedor = Proveedor.builder().id(3L).nombre("Taxi Express").build();

        VueloRecurso recursoTransporte = VueloRecurso.builder()
                .id(30L)
                .registroVueloDiario(registroDiario)
                .proveedor(proveedor)
                .estado(1)
                .build();

        ServicioAsignadoRequest req = new ServicioAsignadoRequest(
                30L, TipoDetalleEnum.TRANSPORTE, null,
                null, null, null, null,
                null, null,
                "AMBOS",
                2   // ← 2 pasajeros
        );

        when(atencionRepository.findById(9L)).thenReturn(Optional.of(atencion));
        when(vueloRecursoRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(recursoTransporte));
        when(servicioMontoService.calcularMonto(recursoTransporte, req))
                .thenReturn(new BigDecimal("215.00")); // 65 + 150, precio fijo del proveedor
        when(servicioAsignadoRepository.save(any(ServicioAsignado.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Act
        atencionService.asignarServicios(9L, List.of(req), 1L);

        // Assert: el subtotal debe seguir siendo 215.00, NO 430.00 (215 x 2)
        ArgumentCaptor<ServicioAsignado> captor = ArgumentCaptor.forClass(ServicioAsignado.class);
        verify(servicioAsignadoRepository).save(captor.capture());
        ServicioAsignado guardado = captor.getValue();

        assertEquals(0, new BigDecimal("215.00").compareTo(guardado.getMontoUnitario()));
        assertEquals(0, new BigDecimal("215.00").compareTo(guardado.getMontoSubtotal()));
        assertEquals(2, guardado.getCantidad()); // la cantidad de pasajeros se conserva (para disponibilidad)
        assertEquals(0, new BigDecimal("215.00").compareTo(atencion.getMontoTotal()));
    }

    @Test
    @DisplayName("asignarServicios — HOTEL con varias habitaciones → el subtotal SÍ escala con la cantidad")
    void asignarServicios_hotelVariasHabitaciones_siMultiplicaPrecioPorCantidad() {
        // Arrange: a diferencia de TRANSPORTE, HOTEL sigue multiplicando por
        // cantidad (cada habitación adicional sí cuesta más).
        RegistroVueloDiario registroDiario = RegistroVueloDiario.builder()
                .id(11L).active(true).build();

        Atencion atencion = Atencion.builder()
                .id(12L)
                .estado(EstadoAtencionEnum.ACTIVO)
                .registroVueloDiario(registroDiario)
                .montoTotal(BigDecimal.ZERO)
                .build();

        Proveedor proveedor = Proveedor.builder().id(4L).nombre("Hotel Costa del Sol").build();

        VueloRecurso recursoHotel = VueloRecurso.builder()
                .id(40L)
                .registroVueloDiario(registroDiario)
                .proveedor(proveedor)
                .estado(1)
                .build();

        ServicioAsignadoRequest req = new ServicioAsignadoRequest(
                40L, TipoDetalleEnum.HOTEL, "DOBLE",
                false, false, false, false,
                LocalDate.now(), LocalDate.now().plusDays(1),
                null,
                3   // ← 3 habitaciones dobles
        );

        when(atencionRepository.findById(12L)).thenReturn(Optional.of(atencion));
        when(vueloRecursoRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(recursoHotel));
        when(servicioMontoService.calcularMonto(recursoHotel, req))
                .thenReturn(new BigDecimal("100.00"));
        when(servicioAsignadoRepository.save(any(ServicioAsignado.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Act
        atencionService.asignarServicios(12L, List.of(req), 1L);

        // Assert: 100.00 x 3 = 300.00
        ArgumentCaptor<ServicioAsignado> captor = ArgumentCaptor.forClass(ServicioAsignado.class);
        verify(servicioAsignadoRepository).save(captor.capture());
        ServicioAsignado guardado = captor.getValue();

        assertEquals(0, new BigDecimal("100.00").compareTo(guardado.getMontoUnitario()));
        assertEquals(0, new BigDecimal("300.00").compareTo(guardado.getMontoSubtotal()));
        assertEquals(0, new BigDecimal("300.00").compareTo(atencion.getMontoTotal()));
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // asignarServicios() — lock pesimista + validación de disponibilidad
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("asignarServicios — usa findByIdForUpdate (lock pesimista), NO findById")
    void asignarServicios_usaFindByIdForUpdate_noFindByIdSimple() {
        // Regresión: si alguien vuelve a cambiar findByIdForUpdate por findById
        // en AtencionServiceImpl, este test debe fallar — es la garantía real
        // contra sobreventa entre agentes concurrentes.
        RegistroVueloDiario registroDiario = RegistroVueloDiario.builder()
                .id(10L).active(true).build();
        Atencion atencion = Atencion.builder()
                .id(50L).estado(EstadoAtencionEnum.ACTIVO)
                .registroVueloDiario(registroDiario).montoTotal(BigDecimal.ZERO)
                .build();
        Proveedor proveedor = Proveedor.builder().id(5L).nombre("Restaurante Test").build();
        VueloRecurso recurso = VueloRecurso.builder()
                .id(60L).registroVueloDiario(registroDiario).proveedor(proveedor).estado(1)
                .build();

        ServicioAsignadoRequest req = new ServicioAsignadoRequest(
                60L, TipoDetalleEnum.RESTAURANTE, null,
                true, false, false, false,
                null, null, null, 1);

        when(atencionRepository.findById(50L)).thenReturn(Optional.of(atencion));
        when(vueloRecursoRepository.findByIdForUpdate(60L)).thenReturn(Optional.of(recurso));
        when(servicioMontoService.calcularMonto(recurso, req)).thenReturn(new BigDecimal("20.00"));
        when(servicioAsignadoRepository.save(any(ServicioAsignado.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        atencionService.asignarServicios(50L, List.of(req), 1L);

        verify(vueloRecursoRepository).findByIdForUpdate(60L);
        verify(vueloRecursoRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("asignarServicios — disponibilidad insuficiente → lanza excepción y NO guarda nada")
    void asignarServicios_disponibilidadInsuficiente_noGuardaNiSumaMonto() {
        RegistroVueloDiario registroDiario = RegistroVueloDiario.builder()
                .id(10L).active(true).build();
        Atencion atencion = Atencion.builder()
                .id(51L).estado(EstadoAtencionEnum.ACTIVO)
                .registroVueloDiario(registroDiario).montoTotal(BigDecimal.ZERO)
                .build();
        Proveedor proveedor = Proveedor.builder().id(6L).nombre("Hotel Sin Cupo").build();
        VueloRecurso recursoHotel = VueloRecurso.builder()
                .id(70L).registroVueloDiario(registroDiario).proveedor(proveedor).estado(1)
                .build();

        ServicioAsignadoRequest req = new ServicioAsignadoRequest(
                70L, TipoDetalleEnum.HOTEL, "SIMPLE",
                false, false, false, false,
                LocalDate.now(), LocalDate.now().plusDays(1), null, 1);

        when(atencionRepository.findById(51L)).thenReturn(Optional.of(atencion));
        when(vueloRecursoRepository.findByIdForUpdate(70L)).thenReturn(Optional.of(recursoHotel));
        // El VueloRecurso ya no tiene cupo — simulamos lo que hace
        // DisponibilidadServiceImpl.validarDisponibilidadHotel cuando 0
        // habitaciones simples quedan disponibles.
        doThrow(new BadRequestException("No hay habitaciones simples disponibles"))
                .when(disponibilidadService)
                .validarDisponibilidadHotel(recursoHotel, "SIMPLE", 1);

        assertThrows(BadRequestException.class,
                () -> atencionService.asignarServicios(51L, List.of(req), 1L));

        // Nada se guarda ni se suma al total de la atención
        verify(servicioAsignadoRepository, never()).save(any());
        assertEquals(0, BigDecimal.ZERO.compareTo(atencion.getMontoTotal()));
    }

// ══════════════════════════════════════════════════════════════════════════════
// findById()
// ══════════════════════════════════════════════════════════════════════════════

    @Test
    void findById_atencionExistente_retornaResponse() {
        // Arrange
        Atencion atencion = Atencion.builder()
                .id(1L)
                .numeroCorrelativo("SGC-000000001")
                .vuelo(vueloActivo)
                .nombre("Juan")
                .apellido("Perez")
                .pnr("ABC123")
                .correo("jp@test.com")
                .montoTotal(java.math.BigDecimal.ZERO)
                .estado(EstadoAtencionEnum.ACTIVO)
                .atendidoPor(agente)
                .build();
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencion));

        var expected = new com.saasa.contingencias.domain.dto.response.AtencionResponse(
                1L, "SGC-000000001", 1L, "PU302", "Juan", "Perez", "ABC123", "jp@test.com",
                java.math.BigDecimal.ZERO, null, null, "ACTIVO", "Juan Perez", null,null,null,null,null,null);
        when(atencionMapper.toResponse(atencion)).thenReturn(expected);

        // Act
        var response = atencionService.findById(1L);

        // Assert
        assertNotNull(response);
        assertEquals("ABC123", response.pnr());
        assertEquals("SGC-000000001", response.numeroCorrelativo());
    }

    @Test
    void findById_atencionNoExistente_lanzaRecursoNoEncontradoException() {
        // Arrange
        when(atencionRepository.findById(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(RecursoNoEncontradoException.class,
                () -> atencionService.findById(999L));
    }

    // ══════════════════════════════════════════════════════════════════════════════
// anular() / restaurar() — control de permisos por rol
// ══════════════════════════════════════════════════════════════════════════════

    @Test
    void anular_agenteAnulaSuPropiaAtencion_cambiaEstadoAAnulado() {
        Usuario agenteAutor = Usuario.builder()
                .id(1L).nombre("Juan").apellido("Perez")
                .correo("agente@saasa.com").rol(RolEnum.AGENTE_SAASA)
                .build();
        Atencion atencion = Atencion.builder()
                .id(30L)
                .numeroCorrelativo("SGC-000000030")
                .estado(EstadoAtencionEnum.ACTIVO)
                .atendidoPor(agenteAutor)
                .vuelo(vueloActivo)
                .build();

        when(atencionRepository.findById(30L)).thenReturn(Optional.of(atencion));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agenteAutor));
        when(atencionRepository.save(any())).thenReturn(atencion);

        atencionService.anular(30L, 1L);

        assertEquals(EstadoAtencionEnum.ANULADO, atencion.getEstado());
        verify(atencionRepository).save(atencion);
    }

    @Test
    void anular_agenteIntentaAnularAtencionDeOtroAgente_lanzaAccesoDenegadoException() {
        Usuario agenteAutor = Usuario.builder()
                .id(1L).nombre("Juan").apellido("Perez")
                .correo("agente1@saasa.com").rol(RolEnum.AGENTE_SAASA)
                .build();
        Usuario otroAgente = Usuario.builder()
                .id(2L).nombre("Maria").apellido("Lopez")
                .correo("agente2@saasa.com").rol(RolEnum.AGENTE_SAASA)
                .build();
        Atencion atencionDeOtro = Atencion.builder()
                .id(31L)
                .numeroCorrelativo("SGC-000000031")
                .estado(EstadoAtencionEnum.ACTIVO)
                .atendidoPor(otroAgente)
                .vuelo(vueloActivo)
                .build();

        when(atencionRepository.findById(31L)).thenReturn(Optional.of(atencionDeOtro));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agenteAutor));

        AccesoDenegadoException ex = assertThrows(AccesoDenegadoException.class,
                () -> atencionService.anular(31L, 1L));
        assertEquals("No puedes anular este reporte porque no lo generaste tú.", ex.getMessage());
        verify(atencionRepository, never()).save(any());
    }

    @Test
    void anular_administradorAnulaAtencionDeCualquierAgente_cambiaEstadoAAnulado() {
        Usuario administrador = Usuario.builder()
                .id(9L).nombre("Ana").apellido("Ruiz")
                .correo("admin@saasa.com").rol(RolEnum.ADMINISTRADOR)
                .build();
        Atencion atencionDeOtro = Atencion.builder()
                .id(32L)
                .numeroCorrelativo("SGC-000000032")
                .estado(EstadoAtencionEnum.ACTIVO)
                .atendidoPor(agente) // atendida por otro usuario (agente del setUp)
                .vuelo(vueloActivo)
                .build();

        when(atencionRepository.findById(32L)).thenReturn(Optional.of(atencionDeOtro));
        when(usuarioRepository.findById(9L)).thenReturn(Optional.of(administrador));
        when(atencionRepository.save(any())).thenReturn(atencionDeOtro);

        atencionService.anular(32L, 9L);

        assertEquals(EstadoAtencionEnum.ANULADO, atencionDeOtro.getEstado());
        verify(atencionRepository).save(atencionDeOtro);
    }

    @Test
    void anular_liderAnulaAtencionDeCualquierAgente_cambiaEstadoAAnulado() {
        Usuario lider = Usuario.builder()
                .id(8L).nombre("Carlos").apellido("Diaz")
                .correo("lider@saasa.com").rol(RolEnum.LIDER_SAASA)
                .build();
        Atencion atencionDeOtro = Atencion.builder()
                .id(33L)
                .numeroCorrelativo("SGC-000000033")
                .estado(EstadoAtencionEnum.ACTIVO)
                .atendidoPor(agente)
                .vuelo(vueloActivo)
                .build();

        when(atencionRepository.findById(33L)).thenReturn(Optional.of(atencionDeOtro));
        when(usuarioRepository.findById(8L)).thenReturn(Optional.of(lider));
        when(atencionRepository.save(any())).thenReturn(atencionDeOtro);

        atencionService.anular(33L, 8L);

        assertEquals(EstadoAtencionEnum.ANULADO, atencionDeOtro.getEstado());
        verify(atencionRepository).save(atencionDeOtro);
    }

    @Test
    void anular_atencionYaAnulada_lanzaBadRequestException() {
        Usuario agenteAutor = Usuario.builder()
                .id(1L).nombre("Juan").apellido("Perez")
                .correo("agente@saasa.com").rol(RolEnum.AGENTE_SAASA)
                .build();
        Atencion atencionAnulada = Atencion.builder()
                .id(34L)
                .numeroCorrelativo("SGC-000000034")
                .estado(EstadoAtencionEnum.ANULADO)
                .atendidoPor(agenteAutor)
                .vuelo(vueloActivo)
                .build();

        when(atencionRepository.findById(34L)).thenReturn(Optional.of(atencionAnulada));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agenteAutor));

        assertThrows(BadRequestException.class, () -> atencionService.anular(34L, 1L));
        verify(atencionRepository, never()).save(any());
    }

    @Test
    void restaurar_agenteRestauraSuPropiaAtencion_cambiaEstadoAActivo() {
        Usuario agenteAutor = Usuario.builder()
                .id(1L).nombre("Juan").apellido("Perez")
                .correo("agente@saasa.com").rol(RolEnum.AGENTE_SAASA)
                .build();
        Atencion atencionAnulada = Atencion.builder()
                .id(35L)
                .numeroCorrelativo("SGC-000000035")
                .estado(EstadoAtencionEnum.ANULADO)
                .atendidoPor(agenteAutor)
                .vuelo(vueloActivo)
                .build();

        when(atencionRepository.findById(35L)).thenReturn(Optional.of(atencionAnulada));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agenteAutor));
        when(atencionRepository.save(any())).thenReturn(atencionAnulada);

        atencionService.restaurar(35L, 1L);

        assertEquals(EstadoAtencionEnum.ACTIVO, atencionAnulada.getEstado());
        verify(atencionRepository).save(atencionAnulada);
    }

    @Test
    void restaurar_agenteIntentaRestaurarAtencionDeOtroAgente_lanzaAccesoDenegadoException() {
        Usuario agenteAutor = Usuario.builder()
                .id(1L).nombre("Juan").apellido("Perez")
                .correo("agente1@saasa.com").rol(RolEnum.AGENTE_SAASA)
                .build();
        Usuario otroAgente = Usuario.builder()
                .id(2L).nombre("Maria").apellido("Lopez")
                .correo("agente2@saasa.com").rol(RolEnum.AGENTE_SAASA)
                .build();
        Atencion atencionDeOtro = Atencion.builder()
                .id(36L)
                .numeroCorrelativo("SGC-000000036")
                .estado(EstadoAtencionEnum.ANULADO)
                .atendidoPor(otroAgente)
                .vuelo(vueloActivo)
                .build();

        when(atencionRepository.findById(36L)).thenReturn(Optional.of(atencionDeOtro));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agenteAutor));

        AccesoDenegadoException ex = assertThrows(AccesoDenegadoException.class,
                () -> atencionService.restaurar(36L, 1L));
        assertEquals("No puedes restaurar este reporte porque no lo generaste tú.", ex.getMessage());
        verify(atencionRepository, never()).save(any());
    }

    @Test
    void restaurar_atencionYaActiva_lanzaBadRequestException() {
        Usuario administrador = Usuario.builder()
                .id(9L).nombre("Ana").apellido("Ruiz")
                .correo("admin@saasa.com").rol(RolEnum.ADMINISTRADOR)
                .build();
        Atencion atencionActiva = Atencion.builder()
                .id(37L)
                .numeroCorrelativo("SGC-000000037")
                .estado(EstadoAtencionEnum.ACTIVO)
                .atendidoPor(agente)
                .vuelo(vueloActivo)
                .build();

        when(atencionRepository.findById(37L)).thenReturn(Optional.of(atencionActiva));
        when(usuarioRepository.findById(9L)).thenReturn(Optional.of(administrador));

        assertThrows(BadRequestException.class, () -> atencionService.restaurar(37L, 9L));
        verify(atencionRepository, never()).save(any());
    }
}