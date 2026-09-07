package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.dto.response.CargaMasivaResponse;
import com.saasa.contingencias.domain.enumeration.ContingenciaEnum;
import com.saasa.contingencias.domain.enumeration.EstadoVueloEnum;
import com.saasa.contingencias.domain.mapping.VueloMapper;
import com.saasa.contingencias.domain.model.Usuario;
import com.saasa.contingencias.domain.model.Vuelo;
import com.saasa.contingencias.domain.repository.UsuarioRepository;
import com.saasa.contingencias.domain.repository.VueloRepository;
import com.saasa.contingencias.util.DateTimeUtil;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VueloExcelServiceImplTest {

    @Mock VueloRepository   vueloRepository;
    @Mock UsuarioRepository usuarioRepository;
    @Mock VueloMapper vueloMapper;
    @Mock com.saasa.contingencias.config.security.EstacionContext estacionContext;
    @Mock com.saasa.contingencias.domain.repository.EstacionRepository estacionRepository;
    @Mock com.saasa.contingencias.domain.repository.LineaAereaRepository lineaAereaRepository;
    @Mock com.saasa.contingencias.domain.repository.EstacionLineaAereaRepository estacionLineaAereaRepository;

    @InjectMocks VueloExcelServiceImpl service;

    private Usuario   admin;
    private LocalDate hoy;
    private LocalDate manana;
    private LocalDate ayer;

    @BeforeEach
    void setUp() {
        admin  = Usuario.builder().id(1L).nombre("Admin").apellido("SAASA").build();
        hoy    = DateTimeUtil.hoyEnLima();
        manana = hoy.plusDays(1);
        ayer   = hoy.minusDays(1);

        var lima = com.saasa.contingencias.domain.model.Estacion.builder()
                .id(1L).codigoIata("LIM").nombre("Lima").estado(1).build();
        var plusUltra = com.saasa.contingencias.domain.model.LineaAerea.builder()
                .id(5L).codigoIata("PUL").nombre("PlusUltra").estado(1).build();
        // El Excel completo pertenece a UNA sola estación+línea aérea: el
        // contexto de trabajo activo (topbar), resuelto por
        // EstacionContext.resolverContextoActivo(estacionId, lineaAereaId).
        lenient().when(estacionContext.resolverContextoActivo(any(), any()))
                .thenReturn(new ScopeEstacionLinea(1L, 5L));
        lenient().when(estacionRepository.findById(1L)).thenReturn(Optional.of(lima));
        lenient().when(lineaAereaRepository.findById(5L)).thenReturn(Optional.of(plusUltra));
    }

    @Test
    @DisplayName("E1 — Todas las filas válidas → registrados = 3, errores vacío")
    void e1_todasFilasValidas_registraTodas() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(vueloRepository.existsByCodigoVueloAndFechaVuelo(any(), any())).thenReturn(false);
        when(vueloRepository.save(any())).thenAnswer(inv -> mockGuardado(inv.getArgument(0)));

        // Todas las filas deben ser de la misma línea aérea del contexto de
        // trabajo activo ("PlusUltra"): el Excel completo pertenece a una
        // sola estación+línea aérea, ya no se auto-crea una LineaAerea por fila.
        MultipartFile file = excelCon(
                fila("PU301", "PlusUltra", "MAD", "LIM", manana,             "CANCELACION", ""),
                fila("PU200", "PlusUltra", "MAD", "LIM", manana.plusDays(1), "DEMORA",       ""),
                fila("PU100", "PlusUltra", "SCL", "LIM", manana.plusDays(2), "PROGRAMADO",   "")
        );

        CargaMasivaResponse result = service.cargarDesdeExcel(file, 1L, null, null);

        assertEquals(3, result.totalRegistrados());
        assertEquals(0, result.totalErrores());
        assertFalse(result.tieneErrores());
    }

    @Test
    @DisplayName("E2 — Excel sin filas de datos → registrados = 0, errores = 0")
    void e2_archivoSinDatos_noCreaVuelos() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(admin));

        MultipartFile file = excelSinDatos();

        CargaMasivaResponse result = service.cargarDesdeExcel(file, 1L, null, null);

        assertEquals(0, result.totalRegistrados());
        assertEquals(0, result.totalErrores());
        verifyNoInteractions(vueloRepository);
    }

    @Test
    @DisplayName("E3 — Fila con fecha anterior a hoy Lima → error en esa fila, resto se registra")
    void e3_filaConFechaPasada_seRegistraErrorYContinua() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(vueloRepository.existsByCodigoVueloAndFechaVuelo(eq("PU302"), eq(manana))).thenReturn(false);
        when(vueloRepository.save(any())).thenAnswer(inv -> mockGuardado(inv.getArgument(0)));

        MultipartFile file = excelCon(
                fila("PU301", "PlusUltra", "LIM", "MAD", ayer,   "CANCELACION", ""),
                fila("PU302", "PlusUltra", "LIM", "BOG", manana, "DEMORA",       "")
        );

        CargaMasivaResponse result = service.cargarDesdeExcel(file, 1L, null, null);

        assertEquals(1, result.totalRegistrados(), "Solo la fila válida debe registrarse");
        assertEquals(1, result.totalErrores(),     "La fila con fecha pasada debe generar 1 error");
        assertTrue(result.errores().get(0).contains("anterior a hoy"),
                "El mensaje debe mencionar que la fecha es anterior a hoy");
    }

    @Test
    @DisplayName("E4 — Vuelo ya existente en BD (mismo código+fecha) → error sin detener el resto")
    void e4_vueloDuplicadoEnBd_seRegistraErrorYContinua() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(vueloRepository.existsByCodigoVueloAndFechaVuelo("PU301", manana)).thenReturn(true);
        when(vueloRepository.existsByCodigoVueloAndFechaVuelo("PU302", manana)).thenReturn(false);
        when(vueloRepository.save(any())).thenAnswer(inv -> mockGuardado(inv.getArgument(0)));

        MultipartFile file = excelCon(
                fila("PU301", "PlusUltra", "LIM", "MAD", manana, "CANCELACION", ""),
                fila("PU302", "PlusUltra", "LIM", "BOG", manana, "DEMORA",       "")
        );

        CargaMasivaResponse result = service.cargarDesdeExcel(file, 1L, null, null);

        assertEquals(1, result.totalRegistrados());
        assertEquals(1, result.totalErrores());
        assertTrue(result.errores().get(0).contains("PU301"));
        assertTrue(result.errores().get(0).toLowerCase().contains("duplicado") ||
                result.errores().get(0).toLowerCase().contains("ya existe"));
    }

    @Test
    @DisplayName("E5 — IATA origen inválido (4 letras) → error en fila, resto continúa")
    void e5_iataOrigenInvalido_errorYContinua() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(vueloRepository.existsByCodigoVueloAndFechaVuelo("PU302", manana.plusDays(1))).thenReturn(false);
        when(vueloRepository.save(any())).thenAnswer(inv -> mockGuardado(inv.getArgument(0)));

        MultipartFile file = excelCon(
                fila("PU301", "PlusUltra", "LIMA", "MAD", manana,             "CANCELACION", ""),
                fila("PU302", "PlusUltra", "LIM",  "BOG", manana.plusDays(1), "DEMORA",       "")
        );

        CargaMasivaResponse result = service.cargarDesdeExcel(file, 1L, null, null);

        assertEquals(1, result.totalRegistrados());
        assertEquals(1, result.totalErrores());
        assertTrue(result.errores().get(0).toLowerCase().contains("iata"));
    }

    @Test
    @DisplayName("E6 — Código de vuelo vacío → error en fila, resto continúa")
    void e6_codigoVueloVacio_errorYContinua() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(vueloRepository.existsByCodigoVueloAndFechaVuelo("PU302", manana.plusDays(1))).thenReturn(false);
        when(vueloRepository.save(any())).thenAnswer(inv -> mockGuardado(inv.getArgument(0)));

        MultipartFile file = excelCon(
                fila("",      "PlusUltra", "LIM", "MAD", manana,             "CANCELACION", ""),
                fila("PU302", "PlusUltra", "LIM", "BOG", manana.plusDays(1), "DEMORA",       "")
        );

        CargaMasivaResponse result = service.cargarDesdeExcel(file, 1L, null, null);

        assertEquals(1, result.totalRegistrados());
        assertEquals(1, result.totalErrores());
        assertTrue(result.errores().get(0).toLowerCase().contains("código") ||
                result.errores().get(0).toLowerCase().contains("codigo"));
    }

    @Test
    @DisplayName("E7 — Contingencia 'Cancelación' (con tilde) → normaliza a CANCELACION y se guarda")
    void e7_contingenciaConTilde_normaliza() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(vueloRepository.existsByCodigoVueloAndFechaVuelo("PU301", manana)).thenReturn(false);

        ArgumentCaptor<Vuelo> captor = ArgumentCaptor.forClass(Vuelo.class);
        when(vueloRepository.save(captor.capture()))
                .thenAnswer(inv -> mockGuardado(inv.getArgument(0)));

        MultipartFile file = excelCon(
                fila("PU301", "PlusUltra", "LIM", "MAD", manana, "Cancelación", "")
        );

        service.cargarDesdeExcel(file, 1L, null, null);

        assertEquals(ContingenciaEnum.CANCELACION, captor.getValue().getTipoContingencia());
    }

    @Test
    @DisplayName("E8 — Contingencia vacía → se usa CANCELACION por defecto, fila se registra")
    void e8_contingenciaVacia_usaCancelacionPorDefecto() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(vueloRepository.existsByCodigoVueloAndFechaVuelo("PU301", manana)).thenReturn(false);

        ArgumentCaptor<Vuelo> captor = ArgumentCaptor.forClass(Vuelo.class);
        when(vueloRepository.save(captor.capture()))
                .thenAnswer(inv -> mockGuardado(inv.getArgument(0)));

        MultipartFile file = excelCon(
                fila("PU301", "PlusUltra", "LIM", "MAD", manana, "", "")
        );

        CargaMasivaResponse result = service.cargarDesdeExcel(file, 1L, null, null);

        assertEquals(1, result.totalRegistrados());
        assertEquals(ContingenciaEnum.CANCELACION, captor.getValue().getTipoContingencia());
    }

    @Test
    @DisplayName("E10 — usuarioId inexistente → RecursoNoEncontradoException antes de leer filas")
    void e10_usuarioInexistente_lanzaNotFound() throws Exception {
        when(usuarioRepository.findById(99L)).thenReturn(Optional.empty());

        MultipartFile file = excelCon(
                fila("PU301", "PlusUltra", "LIM", "MAD", manana, "CANCELACION", "")
        );

        assertThrows(com.saasa.contingencias.config.exception.RecursoNoEncontradoException.class,
                () -> service.cargarDesdeExcel(file, 99L, null, null));
        verifyNoInteractions(vueloRepository);
    }

    @Test
    @DisplayName("E11 — Mezcla de filas válidas e inválidas → import parcial con lista de errores")
    void e11_mixFilasValidasEInvalidas_importParcial() throws Exception {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(admin));

        when(vueloRepository.existsByCodigoVueloAndFechaVuelo("PU302", manana)).thenReturn(true);
        when(vueloRepository.existsByCodigoVueloAndFechaVuelo("PU304", manana.plusDays(3))).thenReturn(false);
        when(vueloRepository.existsByCodigoVueloAndFechaVuelo("PU305", manana.plusDays(4))).thenReturn(false);
        when(vueloRepository.save(any())).thenAnswer(inv -> mockGuardado(inv.getArgument(0)));

        MultipartFile file = excelCon(
                fila("PU301", "PlusUltra", "LIM", "MAD", ayer,               "CANCELACION", ""),
                fila("PU302", "PlusUltra", "LIM", "MAD", manana,             "CANCELACION", ""),
                fila("PU303", "PlusUltra", "LIMA","MAD", manana.plusDays(2),  "CANCELACION", ""),
                fila("PU304", "PlusUltra", "LIM", "BOG", manana.plusDays(3),  "DEMORA",       ""),
                fila("PU305", "PlusUltra", "LIM", "BOG", manana.plusDays(4),  "PROGRAMADO",   "")
        );

        CargaMasivaResponse result = service.cargarDesdeExcel(file, 1L, null, null);

        assertEquals(2, result.totalRegistrados(), "Solo PU304 y PU305 deben registrarse");
        assertEquals(3, result.totalErrores(),     "PU301, PU302 y PU303 deben fallar");
        assertTrue(result.tieneErrores());
    }

    private MultipartFile excelCon(Object[]... filas) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Vuelos");

            Row header = sheet.createRow(0);
            String[] cols = {"codigoVuelo","aerolinea","origen","destino",
                    "fechaVuelo","tipoContingencia","observaciones"};
            for (int c = 0; c < cols.length; c++)
                header.createCell(c).setCellValue(cols[c]);

            for (int i = 0; i < filas.length; i++) {
                Row row = sheet.createRow(1 + i);
                Object[] f = filas[i];
                row.createCell(0).setCellValue((String)  f[0]);
                row.createCell(1).setCellValue((String)  f[1]);
                row.createCell(2).setCellValue((String)  f[2]);
                row.createCell(3).setCellValue((String)  f[3]);
                row.createCell(4).setCellValue(f[4].toString());
                row.createCell(5).setCellValue((String)  f[5]);
                row.createCell(6).setCellValue((String)  f[6]);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new MockMultipartFile("archivo", "test.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }
    }

    private MultipartFile excelSinDatos() throws Exception {
        return excelCon();
    }

    private Object[] fila(String cod, String aero, String orig, String dest,
                          LocalDate fecha, String cont, String obs) {
        return new Object[]{cod, aero, orig, dest, fecha, cont, obs};
    }

    private Vuelo mockGuardado(Vuelo v) {
        return Vuelo.builder()
                .id((long)(Math.random() * 10000))
                .aerolinea(v.getAerolinea())
                .codigoVuelo(v.getCodigoVuelo())
                .origen(v.getOrigen())
                .destino(v.getDestino())
                .fechaVuelo(v.getFechaVuelo())
                .tipoContingencia(v.getTipoContingencia())
                .observaciones(v.getObservaciones())
                .estado(EstadoVueloEnum.ACTIVO)
                .creadoPor(admin)
                .build();
    }
}
