package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.dto.response.CargaMasivaAtencionResponse;
import com.saasa.contingencias.domain.dto.response.CargaMasivaPreviewResponse;
import com.saasa.contingencias.domain.dto.response.DisponibilidadResponse;
import com.saasa.contingencias.domain.dto.response.LoteEstadoResponse;
import com.saasa.contingencias.domain.enumeration.EstadoDetalleLoteEnum;
import com.saasa.contingencias.domain.enumeration.EstadoLoteEnum;
import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.service.IDisponibilidadService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios de AtencionCargaMasivaServiceImpl.
 *
 * FORMATO DEL EXCEL EN TESTS (mismo patrón que VueloExcelServiceImplTest):
 *   Fila 0 = cabecera, datos desde fila 1. Columnas:
 *   0 Nombres | 1 Apellidos | 2 PNR | 3 Correo | 4 Celular |
 *   5 Cant. Pax Restaurante | 6 Desayuno | 7 Almuerzo | 8 Cena
 *
 * IMPORTANTE — esta clase YA NO crea Atenciones (eso se movió a
 * AtencionCargaMasivaCreadorAsyncImpl, que corre en background después
 * de que este servicio guarda el lote). Por eso ya no hay ningún mock de
 * IAtencionService acá: lo que se prueba es que cargarRestauranteDesdeExcel
 * deja el lote y sus filas guardados en CREANDO_ATENCIONES / POR_CREAR con
 * los datos correctos para que la fase async pueda tomarlos. La creación
 * real de Atenciones se prueba en AtencionCargaMasivaCreadorAsyncImplTest,
 * y el envío de vouchers en VoucherLoteOrchestratorImplTest.
 */
@ExtendWith(MockitoExtension.class)
class AtencionCargaMasivaServiceImplTest {

    @Mock IDisponibilidadService disponibilidadService;
    @Mock RegistroVueloDiarioRepository registroVueloDiarioRepository;
    @Mock VueloRecursoRepository vueloRecursoRepository;
    @Mock UsuarioRepository usuarioRepository;
    @Mock CargaMasivaLoteRepository loteRepository;
    @Mock CargaMasivaDetalleRepository detalleRepository;

    private AtencionCargaMasivaServiceImpl service;

    private Usuario agente;
    private Vuelo vuelo;
    private RegistroVueloDiario registroDiario;
    private Proveedor proveedorRestaurante;
    private VueloRecurso restaurante;

    @BeforeEach
    void setUp() {
        service = new AtencionCargaMasivaServiceImpl(
                disponibilidadService, registroVueloDiarioRepository,
                vueloRecursoRepository, usuarioRepository, loteRepository, detalleRepository);

        agente = Usuario.builder().id(1L).nombre("Juan").apellido("Perez").build();
        vuelo = Vuelo.builder().id(1L).codigoVuelo("PU302").build();
        registroDiario = RegistroVueloDiario.builder()
                .id(1L).active(true).vueloItinerario(vuelo).build();
        proveedorRestaurante = Proveedor.builder()
                .id(5L).tipo(TipoProveedorEnum.RESTAURANTE).nombre("El Buen Sabor").estado(1).build();
        restaurante = VueloRecurso.builder()
                .id(10L).proveedor(proveedorRestaurante).registroVueloDiario(registroDiario)
                .capacidadTotal(200).estado(1).build();
    }

    // ════════════════════════════════════════════════════════════════
    // Validaciones previas (archivo / registro / recurso)
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Archivo vacío → BadRequestException")
    void archivoVacio_lanzaBadRequestException() {
        MultipartFile vacio = new MockMultipartFile("archivo", "vacio.xlsx", null, new byte[0]);
        assertThrows(BadRequestException.class,
                () -> service.cargarRestauranteDesdeExcel(vacio, 1L, 10L,null,null, 1L,null));
        verifyNoInteractions(registroVueloDiarioRepository);
    }

    @Test
    @DisplayName("Extensión distinta de .xlsx → BadRequestException")
    void extensionInvalida_lanzaBadRequestException() throws Exception {
        MultipartFile csv = new MockMultipartFile("archivo", "pasajeros.csv",
                "text/csv", "a,b,c".getBytes());
        assertThrows(BadRequestException.class,
                () -> service.cargarRestauranteDesdeExcel(csv, 1L, 10L,null,null, 1L,null));
    }

    @Test
    @DisplayName("Registro diario inactivo → BadRequestException")
    void registroInactivo_lanzaBadRequestException() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agente));
        RegistroVueloDiario inactivo = RegistroVueloDiario.builder()
                .id(1L).active(false).vueloItinerario(vuelo).build();
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(inactivo));

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "1", "SI", "NO", "NO"));

        assertThrows(BadRequestException.class,
                () -> service.cargarRestauranteDesdeExcel(file, 1L, 10L,null,null, 1L,null));
    }

    @Test
    @DisplayName("Recurso no es RESTAURANTE → BadRequestException")
    void recursoNoEsRestaurante_lanzaBadRequestException() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agente));
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));

        Proveedor proveedorHotel = Proveedor.builder()
                .id(6L).tipo(TipoProveedorEnum.HOTEL).nombre("Hotel Test").estado(1).build();
        VueloRecurso hotel = VueloRecurso.builder()
                .id(11L).proveedor(proveedorHotel).registroVueloDiario(registroDiario)
                .capacidadTotal(50).estado(1).build();
        when(vueloRecursoRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(hotel));

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "1", "SI", "NO", "NO"));

        assertThrows(BadRequestException.class,
                () -> service.cargarRestauranteDesdeExcel(file, 1L, 11L,null,null, 1L,null));
    }

    @Test
    @DisplayName("Recurso no pertenece al registro diario indicado → BadRequestException")
    void recursoDeOtroRegistro_lanzaBadRequestException() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agente));
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));

        RegistroVueloDiario otroRegistro = RegistroVueloDiario.builder().id(99L).active(true).build();
        VueloRecurso restauranteDeOtroRegistro = VueloRecurso.builder()
                .id(10L).proveedor(proveedorRestaurante).registroVueloDiario(otroRegistro)
                .capacidadTotal(200).estado(1).build();
        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(restauranteDeOtroRegistro));

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "1", "SI", "NO", "NO"));

        assertThrows(BadRequestException.class,
                () -> service.cargarRestauranteDesdeExcel(file, 1L, 10L,null,null, 1L,null));
    }

    @Test
    @DisplayName("Usuario inexistente → RecursoNoEncontradoException")
    void usuarioInexistente_lanzaNotFound() throws Exception {
        when(usuarioRepository.findById(99L)).thenReturn(Optional.empty());
        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "1", "SI", "NO", "NO"));

        assertThrows(RecursoNoEncontradoException.class,
                () -> service.cargarRestauranteDesdeExcel(file, 1L, 10L,null,null, 99L,null));
        verifyNoInteractions(registroVueloDiarioRepository);
    }

    // ════════════════════════════════════════════════════════════════
    // Pasajero individual
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Pasajero individual válido → guarda 1 fila POR_CREAR, sin grupoId, lote en CREANDO_ATENCIONES")
    void pasajeroIndividual_guardaUnaFilaPorCrear() throws Exception {
        stubsComunes();

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "5", "SI", "SI", "NO"));

        CargaMasivaAtencionResponse response =
                service.cargarRestauranteDesdeExcel(file, 1L, 10L,null,null, 1L,null);

        assertEquals(1, response.totalPasajeros());
        assertEquals(1, response.totalGrupos());
        assertTrue(response.erroresValidacion().isEmpty());

        ArgumentCaptor<CargaMasivaLote> loteCaptor = ArgumentCaptor.forClass(CargaMasivaLote.class);
        verify(loteRepository).save(loteCaptor.capture());
        assertEquals(EstadoLoteEnum.CREANDO_ATENCIONES, loteCaptor.getValue().getEstado());
        assertEquals(1, loteCaptor.getValue().getTotalPasajeros());

        ArgumentCaptor<List<CargaMasivaDetalle>> detallesCaptor = ArgumentCaptor.forClass(List.class);
        verify(detalleRepository).saveAll(detallesCaptor.capture());
        List<CargaMasivaDetalle> detalles = detallesCaptor.getValue();
        assertEquals(1, detalles.size());

        CargaMasivaDetalle d = detalles.get(0);
        assertEquals("JUAN", d.getNombre());
        assertEquals("PEREZ", d.getApellido());
        assertEquals("ABC123", d.getPnr());
        assertEquals("juan@test.com", d.getCorreo());
        assertNull(d.getGrupoId(), "Un pasajero individual no debe tener grupoId");
        assertTrue(d.getEsTitular());
        assertEquals(EstadoDetalleLoteEnum.POR_CREAR, d.getEstado());
        assertNull(d.getAtencionId(), "La Atención todavía no existe en esta fase");
        assertEquals(5, d.getPaxRestaurante());
        assertTrue(d.getDesayuno());
        assertTrue(d.getAlmuerzo());
        assertFalse(d.getCena());

        verify(disponibilidadService).validarDisponibilidadGeneral(restaurante, 5);
    }

    @Test
    @DisplayName("idempotencyKey se guarda en el lote cuando la carga es exitosa")
    void idempotencyKey_seGuardaEnElLote() throws Exception {
        stubsComunes();
        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "5", "SI", "SI", "NO"));

        service.cargarRestauranteDesdeExcel(file, 1L, 10L, null, null, 1L, "clave-123");

        ArgumentCaptor<CargaMasivaLote> loteCaptor = ArgumentCaptor.forClass(CargaMasivaLote.class);
        verify(loteRepository).save(loteCaptor.capture());
        assertEquals("clave-123", loteCaptor.getValue().getIdempotencyKey());
    }

    @Test
    @DisplayName("idempotencyKey en blanco se guarda como null (no como string vacío)")
    void idempotencyKeyEnBlanco_guardaNull() throws Exception {
        stubsComunes();
        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "5", "SI", "SI", "NO"));

        service.cargarRestauranteDesdeExcel(file, 1L, 10L, null, null, 1L, "   ");

        ArgumentCaptor<CargaMasivaLote> loteCaptor = ArgumentCaptor.forClass(CargaMasivaLote.class);
        verify(loteRepository).save(loteCaptor.capture());
        assertNull(loteCaptor.getValue().getIdempotencyKey());
    }

    @Test
    @DisplayName("Carrera de doble insert con la misma idempotencyKey → recupera y devuelve el lote que ganó la carrera, en vez de fallar")
    void idempotencyKey_carreraDeDobleInsert_devuelveLoteExistente() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agente));
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));
        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(restaurante));
        when(loteRepository.save(any(CargaMasivaLote.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("unique constraint"));

        CargaMasivaLote loteGanador = CargaMasivaLote.builder()
                .loteId("lote-ganador").totalPasajeros(1).totalGrupos(1)
                .erroresValidacionJson(null).idempotencyKey("clave-123").build();
        when(loteRepository.findByIdempotencyKey("clave-123")).thenReturn(Optional.of(loteGanador));

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "5", "SI", "SI", "NO"));

        CargaMasivaAtencionResponse response =
                service.cargarRestauranteDesdeExcel(file, 1L, 10L, null, null, 1L, "clave-123");

        assertEquals("lote-ganador", response.loteId());
        verifyNoInteractions(detalleRepository);
    }

    @Test
    @DisplayName("buscarLotePorIdempotencyKey — clave existente devuelve el CargaMasivaAtencionResponse del lote")
    void buscarLotePorIdempotencyKey_claveExistente_devuelveResponse() {
        CargaMasivaLote lote = CargaMasivaLote.builder()
                .loteId("lote-uuid").totalPasajeros(3).totalGrupos(1)
                .erroresValidacionJson("Fila 4: PNR inválido").build();
        when(loteRepository.findByIdempotencyKey("clave-123")).thenReturn(Optional.of(lote));

        Optional<CargaMasivaAtencionResponse> resultado = service.buscarLotePorIdempotencyKey("clave-123");

        assertTrue(resultado.isPresent());
        assertEquals("lote-uuid", resultado.get().loteId());
        assertEquals(3, resultado.get().totalPasajeros());
        assertEquals(List.of("Fila 4: PNR inválido"), resultado.get().erroresValidacion());
    }

    @Test
    @DisplayName("buscarLotePorIdempotencyKey — clave inexistente devuelve Optional vacío")
    void buscarLotePorIdempotencyKey_claveInexistente_devuelveVacio() {
        when(loteRepository.findByIdempotencyKey("no-existe")).thenReturn(Optional.empty());
        assertTrue(service.buscarLotePorIdempotencyKey("no-existe").isEmpty());
    }

    @Test
    @DisplayName("buscarLotePorIdempotencyKey — clave null o en blanco devuelve Optional vacío sin consultar la BD")
    void buscarLotePorIdempotencyKey_claveNulaOBlanco_noConsultaBD() {
        assertTrue(service.buscarLotePorIdempotencyKey(null).isEmpty());
        assertTrue(service.buscarLotePorIdempotencyKey("   ").isEmpty());
        verifyNoInteractions(loteRepository);
    }

    // ════════════════════════════════════════════════════════════════
    // NUEVO: correos CC + firma de conformidad del modal previo a la carga
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ccDestinos + firmaPasajero → se serializan y guardan en el lote (CC_SEPARATOR, sin duplicados/vacíos)")
    void ccDestinosYFirma_seGuardanEnElLote() throws Exception {
        stubsComunes();

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "5", "SI", "SI", "NO"));

        List<String> ccDestinos = List.of(
                "aerolinea@test.com", " proveedor@test.com ",
                "aerolinea@test.com", // duplicado exacto → debe quedar 1 sola vez
                "", "   ");           // vacíos → deben descartarse

        service.cargarRestauranteDesdeExcel(file, 1L, 10L, ccDestinos, "  Juan Pérez Vásquez  ", 1L,null);

        ArgumentCaptor<CargaMasivaLote> loteCaptor = ArgumentCaptor.forClass(CargaMasivaLote.class);
        verify(loteRepository).save(loteCaptor.capture());
        CargaMasivaLote loteGuardado = loteCaptor.getValue();

        assertEquals(
                "aerolinea@test.com" + CargaMasivaLote.CC_SEPARATOR + "proveedor@test.com",
                loteGuardado.getCcDestinosJson());
        assertEquals("Juan Pérez Vásquez", loteGuardado.getFirmaPasajero());
    }

    @Test
    @DisplayName("ccDestinos null/vacío y firmaPasajero en blanco → el lote guarda null en ambos campos")
    void ccDestinosYFirmaVacios_guardanNullEnElLote() throws Exception {
        stubsComunes();

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "5", "SI", "SI", "NO"));

        service.cargarRestauranteDesdeExcel(file, 1L, 10L, List.of(), "   ", 1L,null);

        ArgumentCaptor<CargaMasivaLote> loteCaptor = ArgumentCaptor.forClass(CargaMasivaLote.class);
        verify(loteRepository).save(loteCaptor.capture());
        CargaMasivaLote loteGuardado = loteCaptor.getValue();

        assertNull(loteGuardado.getCcDestinosJson());
        assertNull(loteGuardado.getFirmaPasajero());
    }

    // ════════════════════════════════════════════════════════════════
    // Grupo (mismo PNR)
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Grupo de 3 con mismo PNR → 1 grupoId compartido, datos del servicio solo en la fila titular")
    void grupoDeTres_datosDelServicioSoloEnTitular() throws Exception {
        stubsComunes();

        MultipartFile file = excelCon(
                filaIndividual("MARIA", "LOPEZ", "XYZ789", "maria@test.com", "+51911222333",
                        "3", "SI", "NO", "SI"),
                filaIndividual("CARLOS", "LOPEZ", "XYZ789", "", "", "", "", "", ""),
                filaIndividual("ANA", "LOPEZ", "XYZ789", "", "", "", "", "", "")
        );

        CargaMasivaAtencionResponse response =
                service.cargarRestauranteDesdeExcel(file, 1L, 10L,null,null, 1L,null);

        assertEquals(3, response.totalPasajeros());
        assertEquals(1, response.totalGrupos());
        assertTrue(response.erroresValidacion().isEmpty());

        ArgumentCaptor<List<CargaMasivaDetalle>> detallesCaptor = ArgumentCaptor.forClass(List.class);
        verify(detalleRepository).saveAll(detallesCaptor.capture());
        List<CargaMasivaDetalle> detalles = detallesCaptor.getValue();
        assertEquals(3, detalles.size());

        // Las 3 filas comparten el mismo grupoId (no nulo)
        String grupoId = detalles.get(0).getGrupoId();
        assertNotNull(grupoId);
        assertEquals(grupoId, detalles.get(1).getGrupoId());
        assertEquals(grupoId, detalles.get(2).getGrupoId());

        // Los acompañantes heredan correo/celular del titular (primera fila)
        assertEquals("maria@test.com", detalles.get(0).getCorreo());
        assertEquals("maria@test.com", detalles.get(1).getCorreo());
        assertEquals("maria@test.com", detalles.get(2).getCorreo());
        assertEquals("+51911222333", detalles.get(1).getCelular());

        // Solo la fila titular (idx 0) trae los datos del servicio de restaurante
        assertTrue(detalles.get(0).getEsTitular());
        assertEquals(3, detalles.get(0).getPaxRestaurante());
        assertTrue(detalles.get(0).getDesayuno());
        assertFalse(detalles.get(0).getAlmuerzo());
        assertTrue(detalles.get(0).getCena());

        assertFalse(detalles.get(1).getEsTitular());
        assertNull(detalles.get(1).getPaxRestaurante());
        assertFalse(detalles.get(2).getEsTitular());
        assertNull(detalles.get(2).getPaxRestaurante());

        // La capacidad validada es la del titular (3), no la suma de filas
        verify(disponibilidadService).validarDisponibilidadGeneral(restaurante, 3);
    }

    @Test
    @DisplayName("Acompañante trae su propio correo → se respeta, no se sobrescribe con el del titular")
    void acompananteConCorreoPropio_seRespeta() throws Exception {
        stubsComunes();

        MultipartFile file = excelCon(
                filaIndividual("MARIA", "LOPEZ", "XYZ789", "maria@test.com", "+51911222333",
                        "2", "SI", "NO", "SI"),
                filaIndividual("CARLOS", "LOPEZ", "XYZ789", "carlos.propio@test.com", "", "", "", "", "")
        );

        service.cargarRestauranteDesdeExcel(file, 1L, 10L,null,null, 1L,null);

        ArgumentCaptor<List<CargaMasivaDetalle>> detallesCaptor = ArgumentCaptor.forClass(List.class);
        verify(detalleRepository).saveAll(detallesCaptor.capture());
        assertEquals("carlos.propio@test.com", detallesCaptor.getValue().get(1).getCorreo());
    }

    // ════════════════════════════════════════════════════════════════
    // Validaciones de fila / grupo (parcialidad, igual que VueloExcelService)
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PNR inválido en una fila → se omite esa fila, el resto se procesa")
    void pnrInvalido_omiteFilaYContinua() throws Exception {
        stubsComunes();

        MultipartFile file = excelCon(
                filaIndividual("PEDRO", "RUIZ", "MAL0", "pedro@test.com", "", "1", "NO", "NO", "NO"), // PNR de 4 chars
                filaIndividual("JUAN", "PEREZ", "ABC123", "juan@test.com", "", "1", "SI", "NO", "NO")
        );

        CargaMasivaAtencionResponse response =
                service.cargarRestauranteDesdeExcel(file, 1L, 10L,null,null, 1L,null);

        assertEquals(1, response.totalPasajeros());
        assertEquals(1, response.totalGrupos());
        assertEquals(1, response.erroresValidacion().size());
        assertTrue(response.erroresValidacion().get(0).contains("PNR inválido"));
    }

    @Test
    @DisplayName("Correo con formato inválido → se omite esa fila")
    void correoInvalido_omiteFila() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agente));
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));
        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(restaurante));

        MultipartFile file = excelCon(
                filaIndividual("PEDRO", "RUIZ", "ABC999", "correo-sin-arroba", "", "1", "NO", "NO", "NO")
        );

        assertThrows(BadRequestException.class,
                () -> service.cargarRestauranteDesdeExcel(file, 1L, 10L,null,null, 1L,null));
        // No hay ningún grupo válido → falla rápido, sin guardar nada
        verifyNoInteractions(loteRepository);
        verifyNoInteractions(detalleRepository);
    }

    @Test
    @DisplayName("Celular con formato inválido → se omite esa fila")
    void celularInvalido_omiteFila() throws Exception {
        stubsComunes();

        MultipartFile file = excelCon(
                filaIndividual("PEDRO", "RUIZ", "DEF456", "pedro@test.com", "987654321", "1", "NO", "NO", "NO"), // sin +
                filaIndividual("JUAN", "PEREZ", "ABC123", "juan@test.com", "", "1", "SI", "NO", "NO")
        );

        CargaMasivaAtencionResponse response =
                service.cargarRestauranteDesdeExcel(file, 1L, 10L,null,null, 1L,null);

        assertEquals(1, response.totalPasajeros());
        assertEquals(1, response.erroresValidacion().size());
        assertTrue(response.erroresValidacion().get(0).toLowerCase().contains("celular"));
    }

    @Test
    @DisplayName("Grupo sin correo en la primera fila (titular) → grupo completo omitido")
    void grupoSinCorreoDelTitular_seOmiteGrupoCompleto() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agente));
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));
        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(restaurante));

        MultipartFile file = excelCon(
                filaIndividual("MARIA", "LOPEZ", "XYZ789", "", "", "3", "SI", "NO", "SI"), // titular sin correo
                filaIndividual("CARLOS", "LOPEZ", "XYZ789", "", "", "", "", "", "")
        );

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.cargarRestauranteDesdeExcel(file, 1L, 10L,null,null, 1L,null));
        assertTrue(ex.getMessage().contains("titular"));
        verifyNoInteractions(loteRepository);
    }

    @Test
    @DisplayName("Grupo sin cantidad de pax en la primera fila → grupo completo omitido")
    void grupoSinCantidadPax_seOmiteGrupoCompleto() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agente));
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));
        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(restaurante));

        MultipartFile file = excelCon(
                filaIndividual("MARIA", "LOPEZ", "XYZ789", "maria@test.com", "", "", "SI", "NO", "SI") // sin pax
        );

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.cargarRestauranteDesdeExcel(file, 1L, 10L,null,null, 1L,null));
        assertTrue(ex.getMessage().contains("cantidad de pax"));
        verifyNoInteractions(loteRepository);
    }

    @Test
    @DisplayName("Mezcla de filas válidas e inválidas → import parcial, no detiene el resto")
    void mezclaFilasValidasEInvalidas_importParcial() throws Exception {
        stubsComunes();

        MultipartFile file = excelCon(
                filaIndividual("SIN", "NOMBRE", "GHI789", "malo@test.com", "", "", "", "", ""), // sin pax → error
                filaIndividual("JUAN", "PEREZ", "ABC123", "juan@test.com", "", "1", "SI", "NO", "NO"),
                filaIndividual("PEDRO", "RUIZ", "DEF456", "pedro@test.com", "", "2", "NO", "SI", "NO")
        );

        CargaMasivaAtencionResponse response =
                service.cargarRestauranteDesdeExcel(file, 1L, 10L,null,null, 1L,null);

        assertEquals(2, response.totalPasajeros());
        assertEquals(2, response.totalGrupos());
        assertEquals(1, response.erroresValidacion().size());
        verify(disponibilidadService).validarDisponibilidadGeneral(restaurante, 3); // 1 + 2
    }

    @Test
    @DisplayName("Capacidad insuficiente → la validación de disponibilidad detiene todo, nada se guarda")
    void capacidadInsuficiente_noGuardaNada() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agente));
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));
        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(restaurante));
        doThrow(new BadRequestException("No hay disponibilidad suficiente"))
                .when(disponibilidadService).validarDisponibilidadGeneral(any(), anyInt());

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "", "500", "SI", "NO", "NO"));

        assertThrows(BadRequestException.class,
                () -> service.cargarRestauranteDesdeExcel(file, 1L, 10L,null,null, 1L,null));
        verifyNoInteractions(loteRepository);
        verifyNoInteractions(detalleRepository);
    }

    // ════════════════════════════════════════════════════════════════
// NUEVO: previsualizarRestauranteDesdeExcel — no crea nada, solo
// parsea/agrupa/informa, para el modal de confirmación previo a firmar.
// ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Preview: archivo vacío → BadRequestException, no toca repositorios")
    void preview_archivoVacio_lanzaBadRequestException() {
        MultipartFile vacio = new MockMultipartFile("archivo", "vacio.xlsx", null, new byte[0]);
        assertThrows(BadRequestException.class,
                () -> service.previsualizarRestauranteDesdeExcel(vacio, 1L, 10L));
        verifyNoInteractions(registroVueloDiarioRepository);
    }

    @Test
    @DisplayName("Preview: extensión distinta de .xlsx → BadRequestException")
    void preview_extensionInvalida_lanzaBadRequestException() {
        MultipartFile csv = new MockMultipartFile("archivo", "pasajeros.csv",
                "text/csv", "a,b,c".getBytes());
        assertThrows(BadRequestException.class,
                () -> service.previsualizarRestauranteDesdeExcel(csv, 1L, 10L));
    }

    @Test
    @DisplayName("Preview: registro diario no encontrado → RecursoNoEncontradoException")
    void preview_registroNoEncontrado_lanzaNotFound() throws Exception {
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.empty());
        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "1", "SI", "NO", "NO"));

        assertThrows(RecursoNoEncontradoException.class,
                () -> service.previsualizarRestauranteDesdeExcel(file, 1L, 10L));
    }

    @Test
    @DisplayName("Preview: registro diario inactivo → BadRequestException")
    void preview_registroInactivo_lanzaBadRequestException() throws Exception {
        RegistroVueloDiario inactivo = RegistroVueloDiario.builder()
                .id(1L).active(false).vueloItinerario(vuelo).build();
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(inactivo));

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "1", "SI", "NO", "NO"));

        assertThrows(BadRequestException.class,
                () -> service.previsualizarRestauranteDesdeExcel(file, 1L, 10L));
    }

    @Test
    @DisplayName("Preview: recurso no encontrado → RecursoNoEncontradoException (usa findById, sin lock)")
    void preview_recursoNoEncontrado_lanzaNotFound() throws Exception {
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));
        when(vueloRecursoRepository.findById(10L)).thenReturn(Optional.empty());

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "1", "SI", "NO", "NO"));

        assertThrows(RecursoNoEncontradoException.class,
                () -> service.previsualizarRestauranteDesdeExcel(file, 1L, 10L));
        // El preview NUNCA debe bloquear la fila con lock pesimista
        verify(vueloRecursoRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("Preview: recurso no es RESTAURANTE → BadRequestException")
    void preview_recursoNoEsRestaurante_lanzaBadRequestException() throws Exception {
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));
        Proveedor proveedorHotel = Proveedor.builder()
                .id(6L).tipo(TipoProveedorEnum.HOTEL).nombre("Hotel Test").estado(1).build();
        VueloRecurso hotel = VueloRecurso.builder()
                .id(11L).proveedor(proveedorHotel).registroVueloDiario(registroDiario)
                .capacidadTotal(50).estado(1).build();
        when(vueloRecursoRepository.findById(11L)).thenReturn(Optional.of(hotel));

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "1", "SI", "NO", "NO"));

        assertThrows(BadRequestException.class,
                () -> service.previsualizarRestauranteDesdeExcel(file, 1L, 11L));
    }

    @Test
    @DisplayName("Preview: recurso de otro registro diario → BadRequestException")
    void preview_recursoDeOtroRegistro_lanzaBadRequestException() throws Exception {
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));
        RegistroVueloDiario otroRegistro = RegistroVueloDiario.builder().id(99L).active(true).build();
        VueloRecurso restauranteDeOtroRegistro = VueloRecurso.builder()
                .id(10L).proveedor(proveedorRestaurante).registroVueloDiario(otroRegistro)
                .capacidadTotal(200).estado(1).build();
        when(vueloRecursoRepository.findById(10L)).thenReturn(Optional.of(restauranteDeOtroRegistro));

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "1", "SI", "NO", "NO"));

        assertThrows(BadRequestException.class,
                () -> service.previsualizarRestauranteDesdeExcel(file, 1L, 10L));
    }

    @Test
    @DisplayName("Preview: recurso inactivo → BadRequestException")
    void preview_recursoInactivo_lanzaBadRequestException() throws Exception {
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));
        VueloRecurso restauranteInactivo = VueloRecurso.builder()
                .id(10L).proveedor(proveedorRestaurante).registroVueloDiario(registroDiario)
                .capacidadTotal(200).estado(0).build();
        when(vueloRecursoRepository.findById(10L)).thenReturn(Optional.of(restauranteInactivo));

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "1", "SI", "NO", "NO"));

        assertThrows(BadRequestException.class,
                () -> service.previsualizarRestauranteDesdeExcel(file, 1L, 10L));
    }

    @Test
    @DisplayName("Preview: pasajero individual válido → 1 grupo, 1 pasajero, sin crear nada")
    void preview_pasajeroIndividual_muestraUnGrupoSinCrearNada() throws Exception {
        stubsPreviewComunes(50);

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "5", "SI", "SI", "NO"));

        CargaMasivaPreviewResponse preview =
                service.previsualizarRestauranteDesdeExcel(file, 1L, 10L);

        assertEquals(1, preview.totalPasajeros());
        assertEquals(1, preview.totalGrupos());
        assertTrue(preview.erroresValidacion().isEmpty());
        assertEquals(5, preview.totalPaxSolicitado());
        assertEquals(50, preview.capacidadDisponible());
        assertFalse(preview.excedeCapacidad());

        CargaMasivaPreviewResponse.GrupoPreview grupo = preview.grupos().get(0);
        assertEquals("ABC123", grupo.pnr());
        assertEquals("JUAN PEREZ", grupo.nombreTitular());
        assertEquals("juan@test.com", grupo.correoTitular());
        assertEquals(1, grupo.integrantes());
        assertEquals(5, grupo.paxRestaurante());
        assertTrue(grupo.desayuno());
        assertTrue(grupo.almuerzo());
        assertFalse(grupo.cena());

        // Lo más importante del preview: NO debe crear ni guardar nada
        verifyNoInteractions(loteRepository);
        verifyNoInteractions(detalleRepository);
        verify(vueloRecursoRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("Preview: grupo de 3 con mismo PNR → 1 grupo con 3 integrantes listados")
    void preview_grupoDeTres_muestraIntegrantes() throws Exception {
        stubsPreviewComunes(50);

        MultipartFile file = excelCon(
                filaIndividual("MARIA", "LOPEZ", "XYZ789", "maria@test.com", "+51911222333",
                        "3", "SI", "NO", "SI"),
                filaIndividual("CARLOS", "LOPEZ", "XYZ789", "", "", "", "", "", ""),
                filaIndividual("ANA", "LOPEZ", "XYZ789", "", "", "", "", "", "")
        );

        CargaMasivaPreviewResponse preview =
                service.previsualizarRestauranteDesdeExcel(file, 1L, 10L);

        assertEquals(3, preview.totalPasajeros());
        assertEquals(1, preview.totalGrupos());

        CargaMasivaPreviewResponse.GrupoPreview grupo = preview.grupos().get(0);
        assertEquals("XYZ789", grupo.pnr());
        assertEquals("MARIA LOPEZ", grupo.nombreTitular());
        assertEquals(3, grupo.integrantes());
        assertEquals(List.of("MARIA LOPEZ", "CARLOS LOPEZ", "ANA LOPEZ"), grupo.nombresIntegrantes());
        assertEquals(3, grupo.paxRestaurante());
    }

    @Test
    @DisplayName("Preview: PNR inválido en una fila → aparece en erroresValidacion, no bloquea el resto")
    void preview_pnrInvalido_reportaErrorSinBloquear() throws Exception {
        stubsPreviewComunes(50);

        MultipartFile file = excelCon(
                filaIndividual("PEDRO", "RUIZ", "MAL0", "pedro@test.com", "", "1", "NO", "NO", "NO"),
                filaIndividual("JUAN", "PEREZ", "ABC123", "juan@test.com", "", "1", "SI", "NO", "NO")
        );

        CargaMasivaPreviewResponse preview =
                service.previsualizarRestauranteDesdeExcel(file, 1L, 10L);

        assertEquals(1, preview.totalPasajeros());
        assertEquals(1, preview.totalGrupos());
        assertEquals(1, preview.erroresValidacion().size());
        assertTrue(preview.erroresValidacion().get(0).contains("PNR inválido"));
    }

    @Test
    @DisplayName("Preview: grupo sin correo del titular → NO lanza excepción, queda fuera de grupos y aparece el error")
    void preview_grupoSinCorreoDelTitular_noLanzaExcepcion() throws Exception {
        stubsPreviewComunes(50);

        MultipartFile file = excelCon(
                filaIndividual("MARIA", "LOPEZ", "XYZ789", "", "", "3", "SI", "NO", "SI"),
                filaIndividual("CARLOS", "LOPEZ", "XYZ789", "", "", "", "", "", "")
        );

        // A diferencia de cargarRestauranteDesdeExcel, el preview NO debe
        // lanzar aunque no quede ningún grupo válido.
        CargaMasivaPreviewResponse preview =
                service.previsualizarRestauranteDesdeExcel(file, 1L, 10L);

        assertEquals(0, preview.totalGrupos());
        assertEquals(0, preview.totalPasajeros());
        assertTrue(preview.grupos().isEmpty());
        assertEquals(1, preview.erroresValidacion().size());
        assertTrue(preview.erroresValidacion().get(0).contains("titular"));
        verifyNoInteractions(loteRepository);
    }

    @Test
    @DisplayName("Preview: grupo sin cantidad de pax del titular → mismo comportamiento, sin excepción")
    void preview_grupoSinPaxDelTitular_noLanzaExcepcion() throws Exception {
        stubsPreviewComunes(50);

        MultipartFile file = excelCon(
                filaIndividual("MARIA", "LOPEZ", "XYZ789", "maria@test.com", "", "", "SI", "NO", "SI")
        );

        CargaMasivaPreviewResponse preview =
                service.previsualizarRestauranteDesdeExcel(file, 1L, 10L);

        assertEquals(0, preview.totalGrupos());
        assertTrue(preview.erroresValidacion().get(0).contains("cantidad de pax"));
    }

    @Test
    @DisplayName("Preview: total de pax solicitado excede la capacidad disponible → excedeCapacidad = true, pero no lanza")
    void preview_excedeCapacidad_marcaFlagSinLanzar() throws Exception {
        stubsPreviewComunes(2); // capacidad disponible muy chica

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "10", "SI", "NO", "NO"));

        CargaMasivaPreviewResponse preview =
                service.previsualizarRestauranteDesdeExcel(file, 1L, 10L);

        assertEquals(10, preview.totalPaxSolicitado());
        assertEquals(2, preview.capacidadDisponible());
        assertTrue(preview.excedeCapacidad());
        // Sigue siendo solo un preview: no lanza, no crea nada
        verifyNoInteractions(loteRepository);
    }

    @Test
    @DisplayName("Preview: capacidad suficiente → excedeCapacidad = false")
    void preview_capacidadSuficiente_noMarcaFlag() throws Exception {
        stubsPreviewComunes(100);

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "10", "SI", "NO", "NO"));

        CargaMasivaPreviewResponse preview =
                service.previsualizarRestauranteDesdeExcel(file, 1L, 10L);

        assertFalse(preview.excedeCapacidad());
    }

    @Test
    @DisplayName("Preview: el recurso no aparece en obtenerDisponibilidad → capacidadDisponible null, excedeCapacidad false")
    void preview_recursoSinDisponibilidad_capacidadNull() throws Exception {
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));
        when(vueloRecursoRepository.findById(10L)).thenReturn(Optional.of(restaurante));
        // obtenerDisponibilidad no trae ningún restaurante con ese vueloRecursoId
        when(disponibilidadService.obtenerDisponibilidad(1L))
                .thenReturn(new DisponibilidadResponse(List.of(), List.of(), List.of()));

        MultipartFile file = excelCon(filaIndividual("JUAN", "PEREZ", "ABC123",
                "juan@test.com", "+51987654321", "1", "SI", "NO", "NO"));

        CargaMasivaPreviewResponse preview =
                service.previsualizarRestauranteDesdeExcel(file, 1L, 10L);

        assertNull(preview.capacidadDisponible());
        assertFalse(preview.excedeCapacidad());
    }

    // ════════════════════════════════════════════════════════════════
    // Plantilla
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("generarPlantillaRestaurante produce un .xlsx no vacío con hoja Plantilla e Instrucciones")
    void generarPlantilla_produceExcelValido() throws Exception {
        byte[] bytes = service.generarPlantillaRestaurante();
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        try (XSSFWorkbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            assertNotNull(wb.getSheet("Plantilla"));
            assertNotNull(wb.getSheet("Instrucciones"));
            Row header = wb.getSheet("Plantilla").getRow(0);
            assertEquals("Nombres", header.getCell(0).getStringCellValue());
            assertEquals("PNR", header.getCell(2).getStringCellValue());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // consultarEstadoLote
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("consultarEstadoLote — lote inexistente → RecursoNoEncontradoException")
    void consultarEstadoLote_loteInexistente_lanzaNotFound() {
        when(loteRepository.findByLoteId("no-existe")).thenReturn(Optional.empty());
        assertThrows(RecursoNoEncontradoException.class,
                () -> service.consultarEstadoLote("no-existe"));
    }

    @Test
    @DisplayName("consultarEstadoLote — mapea correctamente lote + detalle")
    void consultarEstadoLote_mapeaCorrectamente() {
        CargaMasivaLote lote = CargaMasivaLote.builder()
                .id(1L).loteId("lote-uuid").estado(EstadoLoteEnum.PROCESANDO)
                .totalPasajeros(2).procesados(1).exitosos(1).fallidos(0)
                .build();
        when(loteRepository.findByLoteId("lote-uuid")).thenReturn(Optional.of(lote));

        CargaMasivaDetalle d1 = CargaMasivaDetalle.builder()
                .id(1L).correlativo("SGC-01").pnr("ABC123").nombreCompleto("Juan Perez")
                .esTitular(true).estado(EstadoDetalleLoteEnum.ENVIADO).build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(d1));

        LoteEstadoResponse response = service.consultarEstadoLote("lote-uuid");

        assertEquals("lote-uuid", response.loteId());
        assertEquals("PROCESANDO", response.estado());
        assertEquals(1, response.detalle().size());
        assertEquals("SGC-01", response.detalle().get(0).correlativo());
        assertTrue(response.detalle().get(0).titular());
        assertEquals("ENVIADO", response.detalle().get(0).estado());
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    private void stubsComunes() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(agente));
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));
        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(restaurante));

        // NUEVO: sin esto, loteRepository.save(...) devuelve null y el servicio
        // truena en lote.getLoteId() justo después de guardarlo.
        when(loteRepository.save(any(CargaMasivaLote.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** Excel con UNA fila de cabecera (fila 0) y datos desde fila 1 — mismo patrón que VueloExcelServiceImplTest. */
    private MultipartFile excelCon(Object[]... filas) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Plantilla");
            Row header = sheet.createRow(0);
            String[] cols = {"Nombres", "Apellidos", "PNR", "Correo", "Celular",
                    "Cant. Pax Restaurante", "Desayuno", "Almuerzo", "Cena"};
            for (int c = 0; c < cols.length; c++) header.createCell(c).setCellValue(cols[c]);

            for (int i = 0; i < filas.length; i++) {
                Row row = sheet.createRow(1 + i);
                Object[] f = filas[i];
                for (int c = 0; c < f.length; c++) {
                    row.createCell(c).setCellValue((String) f[c]);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new MockMultipartFile("archivo", "carga.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }
    }

    private Object[] filaIndividual(String nombre, String apellido, String pnr, String correo,
                                    String celular, String pax, String desayuno,
                                    String almuerzo, String cena) {
        return new Object[]{nombre, apellido, pnr, correo, celular, pax, desayuno, almuerzo, cena};
    }

    /**
     * NUEVO — stubs comunes para previsualizarRestauranteDesdeExcel: usa
     * findById (SIN lock, a diferencia de stubsComunes que usa
     * findByIdForUpdate para cargarRestauranteDesdeExcel) y arma una
     * DisponibilidadResponse con el restaurante de prueba, con la capacidad
     * disponible indicada.
     */
    private void stubsPreviewComunes(int capacidadDisponible) {
        when(registroVueloDiarioRepository.findById(1L)).thenReturn(Optional.of(registroDiario));
        when(vueloRecursoRepository.findById(10L)).thenReturn(Optional.of(restaurante));

        DisponibilidadResponse.RecursoDisponibleResponse recursoDisponible =
                new DisponibilidadResponse.RecursoDisponibleResponse(
                        10L, 5L, "El Buen Sabor", "RESTAURANTE", "correo@test.com",
                        null, null, null,
                        null, null, null,
                        null, null, null,
                        null, null, null,
                        200, 200 - capacidadDisponible, capacidadDisponible,
                        capacidadDisponible == 0);

        when(disponibilidadService.obtenerDisponibilidad(1L))
                .thenReturn(new DisponibilidadResponse(List.of(), List.of(), List.of(recursoDisponible)));
    }
}
