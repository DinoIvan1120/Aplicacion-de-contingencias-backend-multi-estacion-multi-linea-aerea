package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.AccesoDenegadoException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.domain.dto.request.ActualizarPasajeroRequest;
import com.saasa.contingencias.domain.dto.request.ActualizarServiciosRequest;
import com.saasa.contingencias.domain.dto.request.ReporteFilterRequest;
import com.saasa.contingencias.domain.dto.response.ReporteDetalleResponse;
import com.saasa.contingencias.domain.enumeration.*;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.AtencionRepository;
import com.saasa.contingencias.domain.repository.ProveedorRepository;
import com.saasa.contingencias.domain.repository.ServicioAsignadoRepository;
import com.saasa.contingencias.domain.repository.UsuarioRepository;
import com.saasa.contingencias.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReporteServiceImplTest {

    @Mock AtencionRepository atencionRepository;
    @Mock ReporteExcelBuilder reporteExcelBuilder;
    @Mock EstacionContext estacionContext;//
    @InjectMocks ReporteServiceImpl reporteService;

    @Mock
    UsuarioRepository usuarioRepository;
    @Mock
    ProveedorRepository proveedorRepository;
    @Mock
    ServicioAsignadoRepository servicioAsignadoRepository;
    @Mock
    IReporteServicioBuilder reporteServicioBuilder;

    @Mock
    IAuditoriaService auditoriaService;

    @Mock
    IPdfGeneratorService pdfGeneratorService;
    @Mock
    IAtencionService atencionService;
    @Mock
    IS3StorageService s3Service;
    @Mock
    IEmailService emailService;
    @Mock
    IWhatsAppService whatsAppService;

    private static final byte[] PDF_BYTES = "PDF-CONTENT".getBytes();
    private static final String PDF_URL = "https://s3.amazonaws.com/bucket/GRUPO-SGC-000000152.pdf";

    private final ReporteFilterRequest filtroVacio =
            new ReporteFilterRequest(null,null,null,null,null,null,null,null,null,null,null);

    @Test
    void findAll_retornaPaginado() {
        when(atencionRepository.findAll(
                any(Specification.class),
                any(Pageable.class)))
                .thenReturn(Page.empty());

        var result = reporteService.findAll(filtroVacio, null, null, null, PageRequest.of(0, 20));

        assertNotNull(result);
        assertEquals(0, result.getTotalElements());
    }

    @Test
    void exportarExcel_retornaBytesNoVacios() {
        byte[] bytesEsperados = new byte[]{1, 2, 3}; // simular contenido no vacío

        when(atencionRepository.findAll(any(Specification.class)))
                .thenReturn(java.util.List.of());
        when(reporteExcelBuilder.build(any(), any(), any()))
                .thenReturn(bytesEsperados); // ← mockear el builder

        byte[] excel = reporteService.exportarExcel(filtroVacio, null, null, null);

        assertNotNull(excel);
        assertTrue(excel.length > 0);
    }

    // ══════════════════════════════════════════════════════════════════════════════
// findByCorrelativo() / findDetalleByAtencionId() — filtrado por proveedor
// ══════════════════════════════════════════════════════════════════════════════

    @Test
    void findByCorrelativo_comoProveedor_soloVeSuPropioServicio() {
        // Arrange
        Vuelo vuelo = Vuelo.builder()
                .id(1L).codigoVuelo("PU301").aerolinea("Plus Ultra")
                .build();

        Atencion atencion = Atencion.builder()
                .id(50L)
                .numeroCorrelativo("SGC-000000050")
                .pnr("ABC123")
                .estado(EstadoAtencionEnum.ACTIVO)
                .nombre("Juan").apellido("Perez")
                .vuelo(vuelo)
                .montoTotal(BigDecimal.valueOf(150))
                .build();

        ServicioAsignado saHotel = ServicioAsignado.builder()
                .id(1L).atencion(atencion).tipoDetalle(TipoDetalleEnum.HOTEL)
                .build();
        ServicioAsignado saTransporte = ServicioAsignado.builder()
                .id(2L).atencion(atencion).tipoDetalle(TipoDetalleEnum.TRANSPORTE)
                .build();

        ReporteDetalleResponse.ServicioDetalleResponse hotelDetalle =
                new ReporteDetalleResponse.ServicioDetalleResponse(
                        1L, 10L, 99L, "Hotel Costa del Sol", "HOTEL",
                        "SIMPLE", 1, BigDecimal.valueOf(100),
                        false, false, false, false, null, null, null, null,
                        null, null,
                        BigDecimal.valueOf(100), null, null);

        ReporteDetalleResponse.ServicioDetalleResponse transporteDetalle =
                new ReporteDetalleResponse.ServicioDetalleResponse(
                        2L, 20L, 20L, "Taxi Express", "TRANSPORTE",
                        null, null, null,
                        false, false, false, false, null, null, null, null,
                        "INDIVIDUAL", 1,
                        BigDecimal.valueOf(50), null, null);

        when(atencionRepository.findByNumeroCorrelativo("SGC-000000050"))
                .thenReturn(java.util.Optional.of(atencion));
        when(servicioAsignadoRepository.findByAtencionId(50L))
                .thenReturn(java.util.List.of(saHotel, saTransporte));
        when(reporteServicioBuilder.buildHotelDetalle(saHotel)).thenReturn(hotelDetalle);
        when(reporteServicioBuilder.buildTransporteDetalle(saTransporte)).thenReturn(transporteDetalle);

        Usuario proveedorUsuario = Usuario.builder()
                .id(5L).correo("transporte@saasa.com").build();
        Proveedor miProveedor = Proveedor.builder().id(20L).nombre("Taxi Express").build();

        when(usuarioRepository.findById(5L)).thenReturn(java.util.Optional.of(proveedorUsuario));
        when(proveedorRepository.findActivoByCorreo("transporte@saasa.com"))
                .thenReturn(java.util.Optional.of(miProveedor));

        // Act — el usuario 5 es proveedor de TRANSPORTE (id 20), no del hotel (id 99)
        var resultado = reporteService.findByCorrelativo("SGC-000000050", "PROVEEDOR", 5L);

        // Assert
        assertNull(resultado.hotel(), "No debe ver el servicio de hotel, que no le pertenece");
        assertNotNull(resultado.transporte(), "Sí debe ver su propio servicio de transporte");
        assertEquals(20L, resultado.transporte().proveedorId());
        assertEquals(0, BigDecimal.valueOf(50).compareTo(resultado.totalGeneral()),
                "El total visible debe ser solo su propio subtotal, no el total de la atención");
    }

    @Test
    void findByCorrelativo_comoAdministrador_veTodosLosServicios() {
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU301").build();
        Atencion atencion = Atencion.builder()
                .id(51L)
                .numeroCorrelativo("SGC-000000051")
                .pnr("XYZ999")
                .estado(EstadoAtencionEnum.ACTIVO)
                .vuelo(vuelo)
                .montoTotal(BigDecimal.valueOf(150))
                .build();

        ServicioAsignado saHotel = ServicioAsignado.builder()
                .id(3L).atencion(atencion).tipoDetalle(TipoDetalleEnum.HOTEL).build();

        ReporteDetalleResponse.ServicioDetalleResponse hotelDetalle =
                new ReporteDetalleResponse.ServicioDetalleResponse(
                        3L, 10L, 99L, "Hotel Costa del Sol", "HOTEL",
                        "SIMPLE", 1, BigDecimal.valueOf(100),
                        false, false, false, false, null, null, null, null,
                        null, null,
                        BigDecimal.valueOf(100), null, null);

        when(atencionRepository.findByNumeroCorrelativo("SGC-000000051"))
                .thenReturn(java.util.Optional.of(atencion));
        when(servicioAsignadoRepository.findByAtencionId(51L))
                .thenReturn(java.util.List.of(saHotel));
        when(reporteServicioBuilder.buildHotelDetalle(saHotel)).thenReturn(hotelDetalle);

        // Act — Administrador, no aplica filtro de proveedor
        var resultado = reporteService.findByCorrelativo("SGC-000000051", "ADMINISTRADOR", 1L);

        // Assert
        assertNotNull(resultado.hotel());
        assertEquals(0, BigDecimal.valueOf(150).compareTo(resultado.totalGeneral()),
                "Para roles internos el total debe ser el monto completo de la atención");
    }

    // Fase 4 — findByCorrelativo() / findDetalleByAtencionId() protegidos por estación
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findByCorrelativo_deOtraEstacion_lanzaAccesoDenegadoException() {
        Atencion atencion = Atencion.builder()
                .id(90L).numeroCorrelativo("SGC-000000090")
                .estado(EstadoAtencionEnum.ACTIVO)
                .build();

        when(atencionRepository.findByNumeroCorrelativo("SGC-000000090"))
                .thenReturn(java.util.Optional.of(atencion));
        doThrow(new AccesoDenegadoException("Fuera de su alcance"))
                .when(estacionContext).validarAccesoLectura(atencion);

        assertThrows(AccesoDenegadoException.class,
                () -> reporteService.findByCorrelativo("SGC-000000090", "ADMINISTRADOR", 1L));
    }

    @Test
    void findDetalleByAtencionId_deOtraEstacion_lanzaAccesoDenegadoException() {
        Atencion atencion = Atencion.builder()
                .id(91L).numeroCorrelativo("SGC-000000091")
                .estado(EstadoAtencionEnum.ACTIVO)
                .build();

        when(atencionRepository.findById(91L)).thenReturn(java.util.Optional.of(atencion));
        doThrow(new AccesoDenegadoException("Fuera de su alcance"))
                .when(estacionContext).validarAccesoLectura(atencion);

        assertThrows(AccesoDenegadoException.class,
                () -> reporteService.findDetalleByAtencionId(91L, "ADMINISTRADOR", 1L));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // resolverServiciosDe() — fallback por grupoId (voucher grupal)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findAll_atencionSinGrupo_noConsultaPorGrupoId() {
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU301").build();
        Atencion atencion = Atencion.builder()
                .id(60L)
                .numeroCorrelativo("SGC-000000060")
                .pnr("ABC123")
                .nombre("Juan").apellido("Perez")
                .estado(EstadoAtencionEnum.ACTIVO)
                .vuelo(vuelo)
                .montoTotal(BigDecimal.valueOf(100))
                .build(); // grupoId = null (default)

        when(atencionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(java.util.List.of(atencion)));
        when(servicioAsignadoRepository.findByAtencionId(60L)).thenReturn(java.util.List.of());

        var resultado = reporteService.findAll(filtroVacio, null, null, null, PageRequest.of(0, 20));

        assertEquals(1, resultado.getTotalElements());
        assertNull(resultado.getContent().get(0).grupoId());
        verify(servicioAsignadoRepository, never()).findByAtencionGrupoId(any());
    }

    @Test
    void findAll_atencionDeGrupoSinServiciosPropios_usaServiciosDelGrupoYPropagaGrupoId() {
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU301").build();
        Atencion acompanante = Atencion.builder()
                .id(61L)
                .numeroCorrelativo("SGC-000000061")
                .pnr("ABC123")
                .nombre("Flor").apellido("Vasquez")
                .estado(EstadoAtencionEnum.ACTIVO)
                .vuelo(vuelo)
                .grupoId("GRUPO-XYZ")
                .montoTotal(BigDecimal.valueOf(300)) // ya propagado por AtencionVoucherServiceImpl
                .build();

        Proveedor hotelProveedor = Proveedor.builder().id(99L).nombre("Hotel Costa del Sol").tipo(TipoProveedorEnum.HOTEL).build();
        VueloRecurso recursoHotel = VueloRecurso.builder().id(10L).proveedor(hotelProveedor).build();
        ServicioAsignado servicioDelTitular = ServicioAsignado.builder()
                .id(1L).tipoDetalle(TipoDetalleEnum.HOTEL)
                .vueloRecurso(recursoHotel)
                .montoSubtotal(BigDecimal.valueOf(300))
                .build();

        when(atencionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(java.util.List.of(acompanante)));
        // El acompañante NO tiene servicios propios (vacío) → debe caer al fallback por grupoId
        when(servicioAsignadoRepository.findByAtencionId(61L)).thenReturn(java.util.List.of());
        when(servicioAsignadoRepository.findByAtencionGrupoId("GRUPO-XYZ"))
                .thenReturn(java.util.List.of(servicioDelTitular));

        var resultado = reporteService.findAll(filtroVacio, null, null, null, PageRequest.of(0, 20));

        var fila = resultado.getContent().get(0);
        assertEquals("GRUPO-XYZ", fila.grupoId(), // ⚠️ ojo: hoy el record se llama "groupId", ver nota abajo
                "El grupoId de la atención debe propagarse al ReporteVoucherResponse");
        assertEquals("Hotel Costa del Sol", fila.hotel(),
                "Debe mostrar el hotel del grupo aunque esta atención no tenga su propio ServicioAsignado");
        assertEquals(0, BigDecimal.valueOf(300).compareTo(fila.hotelTotal()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // buildReporteDetalle() — pasajerosGrupo
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findByCorrelativo_atencionSinGrupo_pasajerosGrupoEsNull() {
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU301").build();
        Atencion atencion = Atencion.builder()
                .id(70L)
                .numeroCorrelativo("SGC-000000070")
                .pnr("ABC123")
                .estado(EstadoAtencionEnum.ACTIVO)
                .nombre("Juan").apellido("Perez")
                .vuelo(vuelo)
                .montoTotal(BigDecimal.valueOf(100))
                .build(); // grupoId = null

        when(atencionRepository.findByNumeroCorrelativo("SGC-000000070"))
                .thenReturn(java.util.Optional.of(atencion));
        when(servicioAsignadoRepository.findByAtencionId(70L)).thenReturn(java.util.List.of());

        var resultado = reporteService.findByCorrelativo("SGC-000000070", null, null);

        assertNull(resultado.pasajerosGrupo());
        verify(atencionRepository, never()).findByGrupoIdOrderByIdAsc(any());
    }

    @Test
    void findByCorrelativo_atencionDeGrupo_incluyeListaDePasajerosDelGrupo() {
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU301").build();
        Atencion titular = Atencion.builder()
                .id(80L).numeroCorrelativo("SGC-000000080")
                .pnr("ABC123").nombre("Juan").apellido("Perez")
                .estado(EstadoAtencionEnum.ACTIVO).vuelo(vuelo)
                .grupoId("GRUPO-ABC")
                .montoTotal(BigDecimal.valueOf(300))
                .build();
        Atencion acompanante = Atencion.builder()
                .id(81L).numeroCorrelativo("SGC-000000081")
                .pnr("ABC123").nombre("Flor").apellido("Vasquez")
                .estado(EstadoAtencionEnum.ACTIVO).vuelo(vuelo)
                .grupoId("GRUPO-ABC")
                .build();

        when(atencionRepository.findByNumeroCorrelativo("SGC-000000080"))
                .thenReturn(java.util.Optional.of(titular));
        when(servicioAsignadoRepository.findByAtencionId(80L)).thenReturn(java.util.List.of());
        when(atencionRepository.findByGrupoIdOrderByIdAsc("GRUPO-ABC"))
                .thenReturn(java.util.List.of(titular, acompanante));

        var resultado = reporteService.findByCorrelativo("SGC-000000080", null, null);

        assertEquals("GRUPO-ABC", resultado.grupoId()); // ⚠️ ver nota sobre el nombre del campo
        assertNotNull(resultado.pasajerosGrupo());
        assertEquals(java.util.List.of("Juan Perez", "Flor Vasquez"), resultado.pasajerosGrupo());
    }

    @Test
    void findByCorrelativo_atencionDeGrupoSinServiciosPropios_usaServiciosDelGrupo() {
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU301").build();
        Atencion acompanante = Atencion.builder()
                .id(91L).numeroCorrelativo("SGC-000000091")
                .pnr("ABC123").nombre("Flor").apellido("Vasquez")
                .estado(EstadoAtencionEnum.ACTIVO).vuelo(vuelo)
                .grupoId("GRUPO-DEF")
                .build();

        ServicioAsignado servicioDelTitular = ServicioAsignado.builder()
                .id(5L).tipoDetalle(TipoDetalleEnum.HOTEL).build();

        ReporteDetalleResponse.ServicioDetalleResponse hotelDetalle =
                new ReporteDetalleResponse.ServicioDetalleResponse(
                        5L, 10L, 99L, "Hotel Costa del Sol", "HOTEL",
                        "SIMPLE", 1, BigDecimal.valueOf(100),
                        false, false, false, false, null, null, null, null,
                        null, null,
                        BigDecimal.valueOf(100), null, null);

        when(atencionRepository.findByNumeroCorrelativo("SGC-000000091"))
                .thenReturn(java.util.Optional.of(acompanante));
        when(servicioAsignadoRepository.findByAtencionId(91L)).thenReturn(java.util.List.of()); // vacío
        when(servicioAsignadoRepository.findByAtencionGrupoId("GRUPO-DEF"))
                .thenReturn(java.util.List.of(servicioDelTitular));
        when(reporteServicioBuilder.buildHotelDetalle(servicioDelTitular)).thenReturn(hotelDetalle);
        when(atencionRepository.findByGrupoIdOrderByIdAsc("GRUPO-DEF"))
                .thenReturn(java.util.List.of(acompanante));

        var resultado = reporteService.findByCorrelativo("SGC-000000091", null, null);

        assertNotNull(resultado.hotel(),
                "Debe mostrar el hotel del grupo aunque esta atención no tenga su propio ServicioAsignado");
        assertEquals("Hotel Costa del Sol", resultado.hotel().proveedorNombre());
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // actualizarPasajero() — edición de nombre/correo/teléfono/PNR (Admin / Líder)
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    void actualizarPasajero_actualizaCamposYRetornaDetalle() {
        // Arrange
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU301").build();
        Atencion atencion = Atencion.builder()
                .id(60L)
                .numeroCorrelativo("SGC-000000060")
                .pnr("OLDPNR")
                .estado(EstadoAtencionEnum.ACTIVO)
                .nombre("Juan").apellido("Perez")
                .correo("juan.viejo@test.com")
                .telefono(null)
                .vuelo(vuelo)
                .montoTotal(BigDecimal.valueOf(200))
                .build();

        ActualizarPasajeroRequest request = new ActualizarPasajeroRequest(
                "Ivan", "Perez Yumbato", "ivan.nuevo@test.com", "+51987654321", "nuevpn",null
        );

        when(atencionRepository.findById(60L)).thenReturn(Optional.of(atencion));
        when(servicioAsignadoRepository.findByAtencionId(60L)).thenReturn(java.util.List.of());
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(
                Usuario.builder().id(1L).nombre("Dino").apellido("Perez").rol(RolEnum.ADMINISTRADOR).build()));

        // Act
        ReporteDetalleResponse resultado = reporteService.actualizarPasajero(60L, request, 1L);

        // Assert — los campos de la entidad quedaron actualizados
        assertEquals("Ivan", atencion.getNombre());
        assertEquals("Perez Yumbato", atencion.getApellido());
        assertEquals("ivan.nuevo@test.com", atencion.getCorreo());
        assertEquals("+51987654321", atencion.getTelefono());
        assertEquals("NUEVPN", atencion.getPnr(), "El PNR debe normalizarse a mayúsculas");

        // Assert — se persistió y se auditó el cambio
        verify(atencionRepository).save(atencion);
        verify(auditoriaService).registrar(
                eq(1L), eq("ACTUALIZAR_PASAJERO"), eq("ATENCIONES"),
                anyString(), eq("ATENCION"), eq(60L), eq("SGC-000000060"));

        // Assert — el detalle devuelto refleja los nuevos datos
        assertEquals("Ivan", resultado.nombrePasajero());
        assertEquals("NUEVPN", resultado.pnr());
    }

    @Test
    void actualizarPasajero_soloActualizaCamposEnviados() {
        // Arrange — solo se envía el teléfono; el resto debe quedar intacto
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU301").build();
        Atencion atencion = Atencion.builder()
                .id(61L)
                .numeroCorrelativo("SGC-000000061")
                .pnr("ABC123")
                .estado(EstadoAtencionEnum.ACTIVO)
                .nombre("Maria").apellido("Lopez")
                .correo("maria@test.com")
                .telefono(null)
                .vuelo(vuelo)
                .montoTotal(BigDecimal.valueOf(120))
                .build();

        ActualizarPasajeroRequest request = new ActualizarPasajeroRequest(
                null, null, null, "+51911222333", null ,null
        );

        when(atencionRepository.findById(61L)).thenReturn(Optional.of(atencion));
        when(servicioAsignadoRepository.findByAtencionId(61L)).thenReturn(java.util.List.of());
        when(usuarioRepository.findById(2L)).thenReturn(Optional.of(
                Usuario.builder().id(2L).nombre("Dino").apellido("Perez").rol(RolEnum.LIDER_SAASA).build()));

        // Act
        reporteService.actualizarPasajero(61L, request, 2L);

        // Assert — nombre, apellido, correo y pnr no cambiaron
        assertEquals("Maria", atencion.getNombre());
        assertEquals("Lopez", atencion.getApellido());
        assertEquals("maria@test.com", atencion.getCorreo());
        assertEquals("ABC123", atencion.getPnr());
        // Assert — solo el teléfono se actualizó
        assertEquals("+51911222333", atencion.getTelefono());
    }

    // NUEVO — idioma del voucher (ES/EN)
    @Test
    void actualizarPasajero_soloIdiomaVoucher_loActualizaAEN() {
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU301").build();
        Atencion atencion = Atencion.builder()
                .id(63L)
                .numeroCorrelativo("SGC-000000063")
                .pnr("ABC123")
                .estado(EstadoAtencionEnum.ACTIVO)
                .nombre("Cecilia").apellido("Castillo")
                .correo("cecilia@test.com")
                .vuelo(vuelo)
                .montoTotal(BigDecimal.valueOf(90))
                .build(); // idiomaVoucher por defecto = ES

        ActualizarPasajeroRequest request = new ActualizarPasajeroRequest(
                null, null, null, null, null, "EN"
        );

        when(atencionRepository.findById(63L)).thenReturn(Optional.of(atencion));
        when(servicioAsignadoRepository.findByAtencionId(63L)).thenReturn(java.util.List.of());
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(
                Usuario.builder().id(1L).nombre("Dino").apellido("Perez").rol(RolEnum.ADMINISTRADOR).build()));

        // Act
        ReporteDetalleResponse resultado = reporteService.actualizarPasajero(63L, request, 1L);

        // Assert
        assertEquals(IdiomaVoucherEnum.EN, atencion.getIdiomaVoucher());
        assertEquals("EN", resultado.idiomaVoucher());
    }

    @Test
    void actualizarPasajero_telefonoVacio_loLimpiaANull() {
        // Arrange — enviar un string en blanco debe limpiar el teléfono (dejarlo null)
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU301").build();
        Atencion atencion = Atencion.builder()
                .id(62L)
                .numeroCorrelativo("SGC-000000062")
                .pnr("ABC123")
                .estado(EstadoAtencionEnum.ACTIVO)
                .nombre("Luis").apellido("Ramos")
                .telefono("+51999888777")
                .vuelo(vuelo)
                .montoTotal(BigDecimal.valueOf(80))
                .build();

        ActualizarPasajeroRequest request = new ActualizarPasajeroRequest(
                null, null, null, "", null,null
        );

        when(atencionRepository.findById(62L)).thenReturn(Optional.of(atencion));
        when(servicioAsignadoRepository.findByAtencionId(62L)).thenReturn(java.util.List.of());
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(
                Usuario.builder().id(1L).nombre("Dino").apellido("Perez").rol(RolEnum.ADMINISTRADOR).build()));

        // Act
        reporteService.actualizarPasajero(62L, request, 1L);

        // Assert
        assertNull(atencion.getTelefono());
    }

    @Test
    void actualizarPasajero_atencionNoExiste_lanzaRecursoNoEncontradoException() {
        // Arrange
        ActualizarPasajeroRequest request = new ActualizarPasajeroRequest(
                "Ivan", null, null, null, null,null
        );
        when(atencionRepository.findById(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(RecursoNoEncontradoException.class,
                () -> reporteService.actualizarPasajero(999L, request, 1L));

        verify(atencionRepository, never()).save(any());
        verifyNoInteractions(auditoriaService);
    }

    // Fase 4 — actualizarServicios() protegido por estación
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void actualizarServicios_deOtraEstacion_lanzaAccesoDenegadoYNoGuarda() {
        Atencion atencion = Atencion.builder()
                .id(93L).numeroCorrelativo("SGC-000000093")
                .estado(EstadoAtencionEnum.ACTIVO)
                .build();
        ActualizarServiciosRequest request = new ActualizarServiciosRequest(
                1L, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        when(atencionRepository.findById(93L)).thenReturn(Optional.of(atencion));
        doThrow(new AccesoDenegadoException("Fuera de su alcance"))
                .when(estacionContext).validarAccesoLectura(atencion);

        assertThrows(AccesoDenegadoException.class,
                () -> reporteService.actualizarServicios(93L, request, 1L));

        verify(atencionRepository, never()).save(any());
    }

    @Test
    void actualizarPasajero_deOtraEstacion_lanzaAccesoDenegadoYNoGuarda() {
        Atencion atencion = Atencion.builder()
                .id(92L).numeroCorrelativo("SGC-000000092")
                .estado(EstadoAtencionEnum.ACTIVO)
                .build();
        ActualizarPasajeroRequest request = new ActualizarPasajeroRequest(
                "Ivan", null, null, null, null, null
        );

        when(atencionRepository.findById(92L)).thenReturn(Optional.of(atencion));
        doThrow(new AccesoDenegadoException("Fuera de su alcance"))
                .when(estacionContext).validarAccesoLectura(atencion);

        assertThrows(AccesoDenegadoException.class,
                () -> reporteService.actualizarPasajero(92L, request, 1L));

        verify(atencionRepository, never()).save(any());
        verifyNoInteractions(auditoriaService);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Regresión — bug: al reenviar/descargar el PDF de un ACOMPAÑANTE (no
    // titular) de un voucher grupal desde el reporte, el PDF salía vacío
    // (solo encabezado y QR, sin hotel/transporte/restaurante).
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void regenerarYEnviarPdf_acompananteSinServiciosPropios_generaPdfConServiciosDelGrupo() {
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU304").build();
        Atencion acompanante = Atencion.builder()
                .id(153L).numeroCorrelativo("SGC-000000153")
                .nombre("DEBORAHH").apellido("ECHEVARRIA RODRIGUEZ")
                .pnr("WSVFKR").correo("deborahh@test.com")
                .estado(EstadoAtencionEnum.ACTIVO).vuelo(vuelo)
                .grupoId("GRUPO-153")
                .build();

        ServicioAsignado servicioDelTitular = ServicioAsignado.builder()
                .id(20L).tipoDetalle(TipoDetalleEnum.HOTEL).build();

        when(atencionRepository.findById(153L)).thenReturn(Optional.of(acompanante));
        when(servicioAsignadoRepository.findByAtencionId(153L)).thenReturn(List.of());
        when(servicioAsignadoRepository.findByAtencionGrupoId("GRUPO-153"))
                .thenReturn(List.of(servicioDelTitular));
        when(pdfGeneratorService.generarVoucher(eq(acompanante), anyList()))
                .thenReturn(PDF_BYTES);
        when(s3Service.subirPdf(any(), anyString())).thenReturn(PDF_URL);

        String resultado = reporteService.regenerarYEnviarPdf(
                153L, 1L, "deborahh@test.com", null,null);

        assertEquals(PDF_URL, resultado);
        verify(pdfGeneratorService).generarVoucher(acompanante, List.of(servicioDelTitular));
        // FIX — regenerarYEnviarPdf() siempre representa un reenvío por
        // ACTUALIZACIÓN del PDF (endpoint /regenerar-pdf), por lo que debe
        // usar enviarVoucherActualizado(), no enviarVoucher() (que es
        // exclusivo del primer envío / asignación inicial).
        verify(emailService).enviarVoucherActualizado(
                eq("deborahh@test.com"),
                eq((List<String>) null),
                eq("SGC-000000153"),
                eq(PDF_BYTES),
                anyString(),eq(IdiomaVoucherEnum.ES));
        verify(emailService, never()).enviarVoucher(any(), any(), any(), any(), any(),any());
    }

    @Test
    void regenerarPdfSoloDescarga_acompananteSinServiciosPropios_generaPdfConServiciosDelGrupo() {
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU304").build();
        Atencion acompanante = Atencion.builder()
                .id(154L).numeroCorrelativo("SGC-000000154")
                .nombre("DOMINIK").apellido("INFANTE SILVA")
                .pnr("WSVFKR").correo("dominik@test.com")
                .estado(EstadoAtencionEnum.ACTIVO).vuelo(vuelo)
                .grupoId("GRUPO-154")
                .build();

        ServicioAsignado servicioDelTitular = ServicioAsignado.builder()
                .id(21L).tipoDetalle(TipoDetalleEnum.TRANSPORTE).build();

        when(atencionRepository.findById(154L)).thenReturn(Optional.of(acompanante));
        when(servicioAsignadoRepository.findByAtencionId(154L)).thenReturn(List.of());
        when(servicioAsignadoRepository.findByAtencionGrupoId("GRUPO-154"))
                .thenReturn(List.of(servicioDelTitular));
        when(pdfGeneratorService.generarVoucher(eq(acompanante), anyList()))
                .thenReturn(PDF_BYTES);
        when(s3Service.subirPdf(any(), anyString())).thenReturn(PDF_URL);
        when(s3Service.generarUrlFirmada(PDF_URL)).thenReturn(PDF_URL + "?firma=xyz");

        String urlFirmada = reporteService.regenerarPdfSoloDescarga(154L);

        assertEquals(PDF_URL + "?firma=xyz", urlFirmada);
        verify(pdfGeneratorService).generarVoucher(acompanante, List.of(servicioDelTitular));
    }

    @Test
    void regenerarYEnviarPdf_titularConServiciosPropios_siguePropioComportamiento() {
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU304").build();
        Atencion titular = Atencion.builder()
                .id(152L).numeroCorrelativo("SGC-000000152")
                .nombre("DOMINIK").apellido("INFANTE SILVA")
                .pnr("WSVFKR").correo("dominik@test.com")
                .estado(EstadoAtencionEnum.ACTIVO).vuelo(vuelo)
                .grupoId("GRUPO-152")
                .build();

        ServicioAsignado servicioPropio = ServicioAsignado.builder()
                .id(22L).tipoDetalle(TipoDetalleEnum.HOTEL).build();

        when(atencionRepository.findById(152L)).thenReturn(Optional.of(titular));
        when(servicioAsignadoRepository.findByAtencionId(152L)).thenReturn(List.of(servicioPropio));
        when(pdfGeneratorService.generarVoucher(eq(titular), anyList()))
                .thenReturn(PDF_BYTES);
        when(s3Service.subirPdf(any(), anyString())).thenReturn(PDF_URL);

        reporteService.regenerarYEnviarPdf(152L, 1L, "dominik@test.com", null,null);

        verify(pdfGeneratorService).generarVoucher(titular, List.of(servicioPropio));
        verify(servicioAsignadoRepository, never()).findByAtencionGrupoId(any());
    }

    // ── NUEVO — FIX voucher grupal en /reportes/{id}/regenerar-pdf y
    // /reportes/{id}/descargar-actualizado: antes estos endpoints ignoraban
    // el grupoId y regeneraban un PDF/correo con el nombre de un solo
    // pasajero, aunque los servicios sí incluían al grupo completo. ──

    @Test
    void regenerarYEnviarPdf_grupoConVariosIntegrantes_generaYEnviaVoucherGrupalConTodosLosNombres() {
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU301").build();
        Atencion titular = Atencion.builder()
                .id(200L).numeroCorrelativo("SGC-000000253")
                .nombre("JIMENA ANALY").apellido("CASTAÑEDA CUBA")
                .pnr("FHGRKL").correo("jimena@test.com")
                .estado(EstadoAtencionEnum.ACTIVO).vuelo(vuelo)
                .grupoId("GRUPO-200")
                .build();
        Atencion acomp1 = Atencion.builder()
                .id(201L).numeroCorrelativo("SGC-000000254")
                .nombre("HERNAN").apellido("OCAMPO QUISPE")
                .pnr("FHGRKL").correo("jimena@test.com")
                .estado(EstadoAtencionEnum.ACTIVO).vuelo(vuelo)
                .grupoId("GRUPO-200")
                .build();
        Atencion acomp2 = Atencion.builder()
                .id(202L).numeroCorrelativo("SGC-000000255")
                .nombre("PABLO").apellido("ECHEVARRÍA GUTIÉRREZ")
                .pnr("FHGRKL").correo("jimena@test.com")
                .estado(EstadoAtencionEnum.ACTIVO).vuelo(vuelo)
                .grupoId("GRUPO-200")
                .build();
        List<Atencion> grupo = List.of(titular, acomp1, acomp2);

        ServicioAsignado servicioDelTitular = ServicioAsignado.builder()
                .id(30L).tipoDetalle(TipoDetalleEnum.HOTEL).build();

        when(atencionRepository.findById(200L)).thenReturn(Optional.of(titular));
        when(atencionRepository.findByGrupoIdOrderByIdAsc("GRUPO-200")).thenReturn(grupo);
        // Los acompañantes NO tienen servicios propios → compartidos = true
        when(servicioAsignadoRepository.findByAtencionId(201L)).thenReturn(List.of());
        when(servicioAsignadoRepository.findByAtencionId(202L)).thenReturn(List.of());
        // resolverServiciosDe(titular) → tiene servicios propios
        when(servicioAsignadoRepository.findByAtencionId(200L)).thenReturn(List.of(servicioDelTitular));
        when(pdfGeneratorService.generarVoucherGrupal(eq(grupo), anyMap(), eq(true)))
                .thenReturn(PDF_BYTES);
        when(s3Service.subirPdf(eq(PDF_BYTES), eq("GRUPO-SGC-000000253.pdf"))).thenReturn(PDF_URL);

        String resultado = reporteService.regenerarYEnviarPdf(
                200L, 1L, "jimena@test.com", null, null);

        assertEquals(PDF_URL, resultado);
        verify(pdfGeneratorService).generarVoucherGrupal(eq(grupo), anyMap(), eq(true));
        // Correo GRUPAL (asunto "(+2)" + lista completa y actual de nombres),
        // no el correo individual de un solo pasajero.
        verify(emailService).reenviarVoucherGrupal(
                eq("jimena@test.com"),
                eq((List<String>) null),
                eq("SGC-000000253 (+2)"),
                eq(PDF_BYTES),
                eq(List.of("JIMENA ANALY CASTAÑEDA CUBA", "HERNAN OCAMPO QUISPE", "PABLO ECHEVARRÍA GUTIÉRREZ")),
                eq(IdiomaVoucherEnum.ES));
        verify(emailService, never()).enviarVoucherActualizado(any(), any(), any(), any(), any(), any());
        verify(emailService, never()).enviarVoucher(any(), any(), any(), any(), any(), any());
        // El PDF/URL grupal se propaga a TODOS los integrantes del grupo.
        verify(atencionRepository).saveAll(grupo);
    }

    @Test
    void regenerarPdfSoloDescarga_grupoConVariosIntegrantes_generaPdfGrupalSinEnviarCorreoNiWhatsapp() {
        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU301").build();
        Atencion titular = Atencion.builder()
                .id(210L).numeroCorrelativo("SGC-000000300")
                .nombre("ANA").apellido("PEREZ")
                .pnr("ABCDEF").correo("ana@test.com")
                .estado(EstadoAtencionEnum.ACTIVO).vuelo(vuelo)
                .grupoId("GRUPO-210")
                .build();
        Atencion acomp1 = Atencion.builder()
                .id(211L).numeroCorrelativo("SGC-000000301")
                .nombre("LUIS").apellido("GOMEZ")
                .pnr("ABCDEF").correo("ana@test.com")
                .estado(EstadoAtencionEnum.ACTIVO).vuelo(vuelo)
                .grupoId("GRUPO-210")
                .build();
        List<Atencion> grupo = List.of(titular, acomp1);

        ServicioAsignado servicioDelTitular = ServicioAsignado.builder()
                .id(31L).tipoDetalle(TipoDetalleEnum.TRANSPORTE).build();

        when(atencionRepository.findById(210L)).thenReturn(Optional.of(titular));
        when(atencionRepository.findByGrupoIdOrderByIdAsc("GRUPO-210")).thenReturn(grupo);
        when(servicioAsignadoRepository.findByAtencionId(211L)).thenReturn(List.of());
        when(servicioAsignadoRepository.findByAtencionId(210L)).thenReturn(List.of(servicioDelTitular));
        when(pdfGeneratorService.generarVoucherGrupal(eq(grupo), anyMap(), eq(true)))
                .thenReturn(PDF_BYTES);
        when(s3Service.subirPdf(eq(PDF_BYTES), eq("GRUPO-SGC-000000300.pdf"))).thenReturn(PDF_URL);
        when(s3Service.generarUrlFirmada(PDF_URL)).thenReturn(PDF_URL + "?firma=xyz");

        String urlFirmada = reporteService.regenerarPdfSoloDescarga(210L);

        assertEquals(PDF_URL + "?firma=xyz", urlFirmada);
        verify(pdfGeneratorService).generarVoucherGrupal(eq(grupo), anyMap(), eq(true));
        verifyNoInteractions(emailService, whatsAppService);
        verify(atencionRepository).saveAll(grupo);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Fase 4 — regenerarYEnviarPdf() / regenerarPdfSoloDescarga() protegidos por estación
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void regenerarYEnviarPdf_deOtraEstacion_lanzaAccesoDenegadoYNoEnvia() {
        Atencion atencion = Atencion.builder()
                .id(220L).numeroCorrelativo("SGC-000000400")
                .estado(EstadoAtencionEnum.ACTIVO)
                .build();

        when(atencionRepository.findById(220L)).thenReturn(Optional.of(atencion));
        doThrow(new AccesoDenegadoException("Fuera de su alcance"))
                .when(estacionContext).validarAccesoLectura(atencion);

        assertThrows(AccesoDenegadoException.class,
                () -> reporteService.regenerarYEnviarPdf(220L, 1L, "correo@test.com", null, null));

        verifyNoInteractions(emailService, whatsAppService, s3Service, pdfGeneratorService);
    }

    @Test
    void regenerarPdfSoloDescarga_deOtraEstacion_lanzaAccesoDenegadoYNoGenera() {
        Atencion atencion = Atencion.builder()
                .id(221L).numeroCorrelativo("SGC-000000401")
                .estado(EstadoAtencionEnum.ACTIVO)
                .build();

        when(atencionRepository.findById(221L)).thenReturn(Optional.of(atencion));
        doThrow(new AccesoDenegadoException("Fuera de su alcance"))
                .when(estacionContext).validarAccesoLectura(atencion);

        assertThrows(AccesoDenegadoException.class,
                () -> reporteService.regenerarPdfSoloDescarga(221L));

        verifyNoInteractions(s3Service, pdfGeneratorService);
    }

}
