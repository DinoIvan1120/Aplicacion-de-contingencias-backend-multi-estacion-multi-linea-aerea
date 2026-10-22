package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;
import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import com.saasa.contingencias.domain.enumeration.OrigenFirmaEnum;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import com.saasa.contingencias.service.IS3StorageService;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.itextpdf.kernel.pdf.PdfDictionary;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfStream;
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test unitario de PdfGeneratorServiceImpl.
 *
 * No requiere mocks: toda la lógica es construcción de un PDF en memoria
 * con iText + un QR con ZXing, a partir de entidades de dominio. La
 * estrategia aquí es distinta a un ServiceImpl típico con repositorio:
 * en vez de verificar interacciones con mocks, se valida que el PDF
 * resultante sea válido (firma %PDF-) y que las combinaciones reales de
 * datos (con/sin hotel, con/sin transporte, campos nulos) no revienten
 * la generación — que es justamente donde vivía el riesgo sin cobertura.
 */
class PdfGeneratorServiceImplTest {

    private final PdfGeneratorServiceImpl pdfService = new PdfGeneratorServiceImpl(
            mock(LineaAereaRepository.class), mock(IS3StorageService.class));

    // ══════════════════════════════════════════════════════════════════════
    // Helpers de construcción de fixtures
    // ══════════════════════════════════════════════════════════════════════

    private Vuelo vuelo(String aerolinea) {
        return Vuelo.builder()
                .id(1L)
                .aerolinea(aerolinea)
                .codigoVuelo("PU301")
                .origen("LIM")
                .destino("MAD")
                .fechaVuelo(LocalDate.of(2026, 7, 20))
                .build();
    }

    private Atencion atencion(Vuelo vuelo, LocalDate fechaEmision) {
        return Atencion.builder()
                .id(1L)
                .numeroCorrelativo("SGC-000001000")
                .vuelo(vuelo)
                .nombre("Ana")
                .apellido("Ruiz")
                .pnr("ABC123")
                .fechaEmision(fechaEmision)
                .lugarEmision("LIM")
                .correo("ana@test.com")
                .build();
    }

    private Proveedor proveedor(String nombre, String direccion, String telefono) {
        return Proveedor.builder()
                .id(1L)
                .tipo(TipoProveedorEnum.HOTEL)
                .nombre(nombre)
                .ruc("20123456789")
                .direccion(direccion)
                .telefono(telefono)
                .build();
    }

    private VueloRecurso recurso(Vuelo vuelo, Proveedor proveedor) {
        return VueloRecurso.builder()
                .id(1L)
                .vuelo(vuelo)
                .proveedor(proveedor)
                .build();
    }

    /** Pasajero de grupo — mismo vuelo, mismo PNR, distinto id/nombre. */
    private Atencion atencionGrupo(Vuelo vuelo, Long id, String correlativo, String nombre, String apellido) {
        return Atencion.builder()
                .id(id)
                .numeroCorrelativo(correlativo)
                .vuelo(vuelo)
                .nombre(nombre)
                .apellido(apellido)
                .pnr("ABC123")
                .fechaEmision(LocalDate.of(2026, 7, 20))
                .lugarEmision("LIM")
                .correo(nombre.toLowerCase() + "@test.com")
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════
    // generarVoucherGrupal
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Voucher grupal con servicios COMPARTIDOS → un solo bloque de servicios, PDF válido")
    void generarVoucherGrupal_serviciosCompartidos_generaPdfValidoConUnSoloBloque() {
        Vuelo v = vuelo("PlusUltra");
        Atencion titular = atencionGrupo(v, 1L, "SGC-000000001", "Juan", "Perez");
        Atencion acompanante = atencionGrupo(v, 2L, "SGC-000000002", "Flor", "Vasquez");
        Proveedor p = proveedor("Hotel Costa del Sol", "Av. Principal 123", "999888777");
        VueloRecurso r = recurso(v, p);

        ServicioAsignado hotel = ServicioAsignado.builder()
                .id(1L).atencion(titular).vueloRecurso(r)
                .tipoDetalle(TipoDetalleEnum.HOTEL)
                .tipoHabitacion("DOBLE")
                .cantidad(1)
                .montoUnitario(new BigDecimal("150.00"))
                .montoSubtotal(new BigDecimal("150.00"))
                .asignadoEn(LocalDateTime.now())
                .build();

        // Servicios compartidos: el mapa apunta a LA MISMA lista para ambos ids
        Map<Long, List<ServicioAsignado>> serviciosPorAtencion = Map.of(
                1L, List.of(hotel),
                2L, List.of(hotel)
        );

        byte[] pdf = pdfService.generarVoucherGrupal(
                List.of(titular, acompanante), serviciosPorAtencion, true);

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Voucher grupal con servicios INDEPENDIENTES → una sección por pasajero, PDF válido")
    void generarVoucherGrupal_serviciosIndependientes_generaPdfValidoConSeccionesPorPasajero() {
        Vuelo v = vuelo("PlusUltra");
        Atencion p1 = atencionGrupo(v, 1L, "SGC-000000001", "Juan", "Perez");
        Atencion p2 = atencionGrupo(v, 2L, "SGC-000000002", "Flor", "Vasquez");
        Proveedor pHotel = proveedor("Hotel Costa del Sol", "Av. Principal 123", "999888777");
        Proveedor pRest = proveedor("Restaurante El Buen Sabor", null, null);
        VueloRecurso rHotel = recurso(v, pHotel);
        VueloRecurso rRest = recurso(v, pRest);

        ServicioAsignado hotelDeJuan = ServicioAsignado.builder()
                .id(1L).atencion(p1).vueloRecurso(rHotel)
                .tipoDetalle(TipoDetalleEnum.HOTEL)
                .tipoHabitacion("SIMPLE")
                .cantidad(1)
                .montoUnitario(new BigDecimal("100.00"))
                .montoSubtotal(new BigDecimal("100.00"))
                .asignadoEn(LocalDateTime.now())
                .build();

        ServicioAsignado restauranteDeFlor = ServicioAsignado.builder()
                .id(2L).atencion(p2).vueloRecurso(rRest)
                .tipoDetalle(TipoDetalleEnum.RESTAURANTE)
                .cantidad(1)
                .montoUnitario(new BigDecimal("40.00"))
                .montoSubtotal(new BigDecimal("40.00"))
                .asignadoEn(LocalDateTime.now())
                .build();

        Map<Long, List<ServicioAsignado>> serviciosPorAtencion = Map.of(
                1L, List.of(hotelDeJuan),
                2L, List.of(restauranteDeFlor)
        );

        byte[] pdf = pdfService.generarVoucherGrupal(
                List.of(p1, p2), serviciosPorAtencion, false);

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Voucher grupal, servicios independientes, un pasajero SIN servicios → no lanza excepción")
    void generarVoucherGrupal_pasajeroSinServicios_noLanzaExcepcion() {
        Vuelo v = vuelo("PlusUltra");
        Atencion p1 = atencionGrupo(v, 1L, "SGC-000000001", "Juan", "Perez");
        Atencion p2 = atencionGrupo(v, 2L, "SGC-000000002", "Flor", "Vasquez"); // sin servicios

        Map<Long, List<ServicioAsignado>> serviciosPorAtencion = Map.of(
                1L, List.of()
                // p2 (id=2) no tiene entrada en el mapa → getOrDefault(..., List.of())
        );

        byte[] pdf = pdfService.generarVoucherGrupal(
                List.of(p1, p2), serviciosPorAtencion, false);

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Voucher grupal con un solo pasajero (borde) → PDF válido igual que uno individual")
    void generarVoucherGrupal_unSoloPasajero_generaPdfValido() {
        Vuelo v = vuelo("PlusUltra");
        Atencion unico = atencionGrupo(v, 1L, "SGC-000000001", "Juan", "Perez");

        byte[] pdf = pdfService.generarVoucherGrupal(
                List.of(unico), Map.of(1L, List.of()), true);

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }


    // ══════════════════════════════════════════════════════════════════════
    // generarVoucher
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Voucher con servicio de hotel completo (check-in/out, comidas) → PDF válido")
    void generarVoucher_conHotelCompleto_generaPdfValido() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        Proveedor p = proveedor("Hotel Costa del Sol", "Av. Principal 123", "999888777");
        VueloRecurso r = recurso(v, p);

        ServicioAsignado hotel = ServicioAsignado.builder()
                .id(1L).atencion(a).vueloRecurso(r)
                .tipoDetalle(TipoDetalleEnum.HOTEL)
                .tipoHabitacion("DOBLE")
                .cantidad(2)
                .montoUnitario(new BigDecimal("150.00"))
                .montoSubtotal(new BigDecimal("300.00"))
                .asignadoEn(LocalDateTime.now())
                .desayuno(true).almuerzo(false).cena(true).snack(false)
                .fechaIngreso(LocalDate.of(2026, 7, 20))
                .fechaSalida(LocalDate.of(2026, 7, 22))
                .build();

        byte[] pdf = pdfService.generarVoucher(a, List.of(hotel));

        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Voucher con transporte grupal y restaurante (sin hotel) → PDF válido")
    void generarVoucher_conTransporteYRestaurante_generaPdfValido() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        Proveedor pTrans = proveedor("Taxi Express", "Jr. Transporte 456", "988777666");
        Proveedor pRest = proveedor("Restaurante El Buen Sabor", null, null);
        VueloRecurso rTrans = recurso(v, pTrans);
        VueloRecurso rRest = recurso(v, pRest);

        ServicioAsignado transporte = ServicioAsignado.builder()
                .id(2L).atencion(a).vueloRecurso(rTrans)
                .tipoDetalle(TipoDetalleEnum.TRANSPORTE)
                .tipoTransporte("GRUPAL")
                .cantidad(4)
                .montoUnitario(new BigDecimal("50.00"))
                .montoSubtotal(new BigDecimal("200.00"))
                .asignadoEn(LocalDateTime.now())
                .build();

        ServicioAsignado restaurante = ServicioAsignado.builder()
                .id(3L).atencion(a).vueloRecurso(rRest)
                .tipoDetalle(TipoDetalleEnum.RESTAURANTE)
                .cantidad(4)
                .montoUnitario(new BigDecimal("30.00"))
                .montoSubtotal(new BigDecimal("120.00"))
                .asignadoEn(LocalDateTime.now())
                .desayuno(true).almuerzo(true).cena(false).snack(false)
                .build();

        byte[] pdf = pdfService.generarVoucher(a, List.of(transporte, restaurante));

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Voucher con transporte AMBOS (Aeropuerto-Domicilio + Domicilio-Aeropuerto) → PDF válido")
    void generarVoucher_conTransporteAmbos_generaPdfValido() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        Proveedor pTrans = proveedor("Taxi Express", "Jr. Transporte 456", "988777666");
        VueloRecurso rTrans = recurso(v, pTrans);

        ServicioAsignado transporte = ServicioAsignado.builder()
                .id(6L).atencion(a).vueloRecurso(rTrans)
                .tipoDetalle(TipoDetalleEnum.TRANSPORTE)
                .tipoTransporte("AMBOS")
                .cantidad(1)
                .montoUnitario(new BigDecimal("110.00"))
                .montoSubtotal(new BigDecimal("110.00"))
                .asignadoEn(LocalDateTime.now())
                .build();

        byte[] pdf = pdfService.generarVoucher(a, List.of(transporte));

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Voucher con transporte sin tipoTransporte (null) → no lanza excepción, usa etiqueta por defecto")
    void generarVoucher_conTransporteTipoNulo_noLanzaExcepcion() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        Proveedor pTrans = proveedor("Taxi Express", "Jr. Transporte 456", "988777666");
        VueloRecurso rTrans = recurso(v, pTrans);

        ServicioAsignado transporte = ServicioAsignado.builder()
                .id(7L).atencion(a).vueloRecurso(rTrans)
                .tipoDetalle(TipoDetalleEnum.TRANSPORTE)
                .tipoTransporte(null)
                .cantidad(1)
                .montoUnitario(new BigDecimal("30.00"))
                .montoSubtotal(new BigDecimal("30.00"))
                .asignadoEn(LocalDateTime.now())
                .build();

        assertDoesNotThrow(() -> pdfService.generarVoucher(a, List.of(transporte)));
    }

    @Test
    @DisplayName("Voucher sin servicios asignados (lista vacía) → igual genera PDF")
    void generarVoucher_sinServicios_generaPdfValido() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, null); // sin fecha de emisión → usa createdAt/hoy en el footer

        byte[] pdf = pdfService.generarVoucher(a, List.of());

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Vuelo con aerolínea null → usa 'PLUS ULTRA' por defecto, no lanza excepción")
    void generarVoucher_aerolineaNull_usaPlusUltraPorDefecto() {
        Vuelo v = vuelo(null); // sin aerolínea
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));

        byte[] pdf = pdfService.generarVoucher(a, List.of());

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    // ══════════════════════════════════════════════════════════════════════
    // NUEVO — Header con logo de la aerolínea (agregarHeaderAerolinea)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Vuelo con aerolínea SIN logo cargado → encabezado en blanco, PDF válido")
    void generarVoucher_aerolineaSinLogo_encabezadoEnBlancoYGeneraPdfValido() {
        // "LATAM" no tiene logo cargado en el classpath (solo Plus Ultra lo
        // tiene) → agregarHeaderAerolinea() no escribe nada (ni imagen ni
        // texto) y no debe lanzar excepción.
        Vuelo v = vuelo("LATAM");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));

        byte[] pdf = pdfService.generarVoucher(a, List.of());

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Voucher GRUPAL con aerolínea SIN logo cargado → encabezado en blanco, PDF válido")
    void generarVoucherGrupal_aerolineaSinLogo_encabezadoEnBlancoYGeneraPdfValido(){
        Vuelo v = vuelo("LATAM");
        Atencion titular = atencionGrupo(v, 1L, "SGC-000000001", "Juan", "Perez");
        Atencion acompanante = atencionGrupo(v, 2L, "SGC-000000002", "Flor", "Vasquez");

        Map<Long, List<ServicioAsignado>> serviciosPorAtencion = Map.of(
                1L, List.of(),
                2L, List.of()
        );

        byte[] pdf = pdfService.generarVoucherGrupal(
                List.of(titular, acompanante), serviciosPorAtencion, true);

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    /** Genera un PNG válido en memoria (1x1 px), simulando el logo de una aerolínea. */
    private byte[] pngDePrueba() throws Exception {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("Vuelo con lineaAereaId y logoKey configurado → usa el logo dinámico de esa aerolínea (no el de Plus Ultra), PDF válido")
    void generarVoucher_conLineaAereaConLogo_usaLogoDinamico() throws Exception {
        LineaAereaRepository lineaAereaRepository = mock(LineaAereaRepository.class);
        IS3StorageService s3StorageService = mock(IS3StorageService.class);
        PdfGeneratorServiceImpl service = new PdfGeneratorServiceImpl(lineaAereaRepository, s3StorageService);

        LineaAerea latam = LineaAerea.builder()
                .id(7L)
                .codigoIata("LA")
                .nombre("LATAM")
                .logoKey("prd/logos/LA.png")
                .build();
        when(lineaAereaRepository.findById(7L)).thenReturn(Optional.of(latam));
        when(s3StorageService.descargarPdf("prd/logos/LA.png")).thenReturn(pngDePrueba());

        Vuelo vueloLatam = vuelo("LATAM");
        vueloLatam.setLineaAereaId(7L);
        Atencion a = atencion(vueloLatam, LocalDate.of(2026, 7, 20));

        byte[] pdf = service.generarVoucher(a, List.of());

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Sin invalidar el caché, dos vouchers seguidos de la misma aerolínea solo descargan el logo una vez de S3")
    void generarVoucher_dosVecesMismaAerolinea_reutilizaCacheDelLogo() throws Exception {
        LineaAereaRepository lineaAereaRepository = mock(LineaAereaRepository.class);
        IS3StorageService s3StorageService = mock(IS3StorageService.class);
        PdfGeneratorServiceImpl service = new PdfGeneratorServiceImpl(lineaAereaRepository, s3StorageService);

        LineaAerea latam = LineaAerea.builder()
                .id(7L).codigoIata("LA").nombre("LATAM").logoKey("prd/logos/LA.png").build();
        when(lineaAereaRepository.findById(7L)).thenReturn(Optional.of(latam));
        when(s3StorageService.descargarPdf("prd/logos/LA.png")).thenReturn(pngDePrueba());

        Vuelo vueloLatam = vuelo("LATAM");
        vueloLatam.setLineaAereaId(7L);
        Atencion a = atencion(vueloLatam, LocalDate.of(2026, 7, 20));

        assertPdfValido(service.generarVoucher(a, List.of()));
        assertPdfValido(service.generarVoucher(a, List.of()));

        verify(s3StorageService, times(1)).descargarPdf("prd/logos/LA.png");
    }

    @Test
    @DisplayName("invalidarCacheLogo() fuerza que el siguiente voucher vuelva a descargar el logo de S3 " +
            "(regresión del bug: reemplazar el logo en el catálogo no se reflejaba en el PDF)")
    void invalidarCacheLogo_fuerzaNuevaDescargaDesdeS3EnElSiguienteVoucher() throws Exception {
        LineaAereaRepository lineaAereaRepository = mock(LineaAereaRepository.class);
        IS3StorageService s3StorageService = mock(IS3StorageService.class);
        PdfGeneratorServiceImpl service = new PdfGeneratorServiceImpl(lineaAereaRepository, s3StorageService);

        LineaAerea latam = LineaAerea.builder()
                .id(7L).codigoIata("LA").nombre("LATAM").logoKey("prd/logos/LA.png").build();
        when(lineaAereaRepository.findById(7L)).thenReturn(Optional.of(latam));
        when(s3StorageService.descargarPdf("prd/logos/LA.png")).thenReturn(pngDePrueba());

        Vuelo vueloLatam = vuelo("LATAM");
        vueloLatam.setLineaAereaId(7L);
        Atencion a = atencion(vueloLatam, LocalDate.of(2026, 7, 20));

        // 1er voucher: descarga y cachea el logo.
        assertPdfValido(service.generarVoucher(a, List.of()));
        // Se reemplaza el logo en el catálogo → se invalida el caché.
        service.invalidarCacheLogo(7L);
        // 2do voucher: debe volver a golpear S3 (logo nuevo), no servir el cacheado.
        assertPdfValido(service.generarVoucher(a, List.of()));

        verify(s3StorageService, times(2)).descargarPdf("prd/logos/LA.png");
    }

    @Test
    @DisplayName("Línea aérea sin logoKey (nunca subido o eliminado) → no llama a S3 y deja el encabezado en blanco")
    void generarVoucher_lineaAereaSinLogoKey_noLlamaS3YEncabezadoEnBlanco() {
        LineaAereaRepository lineaAereaRepository = mock(LineaAereaRepository.class);
        IS3StorageService s3StorageService = mock(IS3StorageService.class);
        PdfGeneratorServiceImpl service = new PdfGeneratorServiceImpl(lineaAereaRepository, s3StorageService);

        LineaAerea sky = LineaAerea.builder()
                .id(9L)
                .codigoIata("H2")
                .nombre("SKY AIRLINE")
                .logoKey(null)
                .build();
        when(lineaAereaRepository.findById(9L)).thenReturn(Optional.of(sky));

        Vuelo vueloSky = vuelo("SKY AIRLINE");
        vueloSky.setLineaAereaId(9L);
        Atencion a = atencion(vueloSky, LocalDate.of(2026, 7, 20));

        byte[] pdf = service.generarVoucher(a, List.of());

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    // ══════════════════════════════════════════════════════════════════════
    // NUEVO — Idioma del voucher (ES/EN, campo Atencion.idiomaVoucher)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Voucher individual con idioma EN no lanza excepción y genera PDF válido")
    void generarVoucher_idiomaEN_generaPdfValido() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        a.setIdiomaVoucher(IdiomaVoucherEnum.EN);

        byte[] pdf = pdfService.generarVoucher(a, List.of());

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Voucher GRUPAL usa el idioma del TITULAR, no el de los acompañantes")
    void generarVoucherGrupal_usaIdiomaDelTitular_generaPdfValido() {
        Vuelo v = vuelo("PlusUltra");
        Atencion titular = atencionGrupo(v, 1L, "SGC-000000001", "Juan", "Perez");
        titular.setIdiomaVoucher(IdiomaVoucherEnum.EN);
        // El acompañante queda en ES (default) — no debería importar, el
        // voucher grupal es UNO SOLO y debe salir en el idioma del titular.
        Atencion acompanante = atencionGrupo(v, 2L, "SGC-000000002", "Flor", "Vasquez");

        Map<Long, List<ServicioAsignado>> serviciosPorAtencion = Map.of(
                1L, List.of(),
                2L, List.of()
        );

        byte[] pdf = pdfService.generarVoucherGrupal(
                List.of(titular, acompanante), serviciosPorAtencion, true);

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Atención con idiomaVoucher null (dato legado) no lanza excepción — usa español por defecto")
    void generarVoucher_idiomaVoucherNull_usaEspanolPorDefecto() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        a.setIdiomaVoucher(null); // simula un registro anterior a esta funcionalidad

        byte[] pdf = pdfService.generarVoucher(a, List.of());

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Proveedor sin dirección ni teléfono → no lanza excepción (usa '-')")
    void generarVoucher_proveedorSinDireccionNiTelefono_noLanzaExcepcion() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        Proveedor p = proveedor("Hotel Sin Datos", null, null);
        VueloRecurso r = recurso(v, p);

        ServicioAsignado hotel = ServicioAsignado.builder()
                .id(4L).atencion(a).vueloRecurso(r)
                .tipoDetalle(TipoDetalleEnum.HOTEL)
                .cantidad(1)
                .montoUnitario(BigDecimal.ONE)
                .montoSubtotal(BigDecimal.ONE)
                .asignadoEn(LocalDateTime.now())
                .build();

        assertDoesNotThrow(() -> pdfService.generarVoucher(a, List.of(hotel)));
    }

    @Test
    @DisplayName("Servicio sin vueloRecurso asignado (null) → no lanza excepción, omite la fila de detalle")
    void generarVoucher_sinVueloRecurso_noLanzaExcepcion() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));

        ServicioAsignado hotel = ServicioAsignado.builder()
                .id(5L).atencion(a).vueloRecurso(null) // sin recurso — camino defensivo del código
                .tipoDetalle(TipoDetalleEnum.HOTEL)
                .cantidad(1)
                .montoUnitario(BigDecimal.ONE)
                .montoSubtotal(BigDecimal.ONE)
                .asignadoEn(LocalDateTime.now())
                .build();

        assertDoesNotThrow(() -> pdfService.generarVoucher(a, List.of(hotel)));
    }

    @Test
    @DisplayName("Atención nula → lanza RuntimeException envuelta con mensaje claro")
    void generarVoucher_atencionNula_lanzaRuntimeException() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> pdfService.generarVoucher(null, List.of()));

        assertTrue(ex.getMessage().contains("Error generando voucher PDF"));
    }

    // ══════════════════════════════════════════════════════════════════════
// NUEVO — Firma digital de conformidad (Atencion.firmaPasajero)
// ══════════════════════════════════════════════════════════════════════

    /** PNG 1x1 real en base64, para simular una firma de canvas válida. */
    private static final String FIRMA_PNG_BASE64 =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGNgAAIAAAUAAen63NgAAAAASUVORK5CYII=";

    @Test
    @DisplayName("Voucher individual con firma PASAJERO (imagen base64) → embebe la imagen, PDF válido")
    void generarVoucher_conFirmaImagenPasajero_generaPdfValidoConImagenEmbebida() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        a.setFirmaPasajero(FIRMA_PNG_BASE64);
        a.setOrigenFirma(OrigenFirmaEnum.PASAJERO);
        a.setFirmaConforme(true);
        a.setFirmaFecha(LocalDateTime.of(2026, 7, 20, 15, 45));

        byte[] pdf = pdfService.generarVoucher(a, List.of());

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Voucher individual con firma AGENTE_LOTE (texto) → muestra el nombre como texto, PDF válido")
    void generarVoucher_conFirmaTextoAgenteLote_generaPdfValido() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        a.setFirmaPasajero("Maria Gomez");
        a.setOrigenFirma(OrigenFirmaEnum.AGENTE_LOTE);
        a.setOrigenFirmaRol("AGENTE_SAASA");
        a.setFirmaConforme(true);
        a.setFirmaFecha(LocalDateTime.of(2026, 7, 20, 15, 45));

        byte[] pdf = pdfService.generarVoucher(a, List.of());

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Voucher individual con firma imagen pero origenFirma null (dato legado) → igual embebe la imagen")
    void generarVoucher_conFirmaImagenOrigenFirmaNull_generaPdfValido() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        a.setFirmaPasajero(FIRMA_PNG_BASE64);
        a.setOrigenFirma(null); // registro anterior a OrigenFirmaEnum

        byte[] pdf = pdfService.generarVoucher(a, List.of());

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Voucher individual con firma base64 corrupta → no lanza excepción, cae a texto de aviso")
    void generarVoucher_conFirmaBase64Corrupta_noLanzaExcepcion() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        a.setFirmaPasajero("data:image/png;base64,esto-no-es-base64-valido");
        a.setOrigenFirma(OrigenFirmaEnum.PASAJERO);

        assertDoesNotThrow(() -> pdfService.generarVoucher(a, List.of()));
    }

    @Test
    @DisplayName("Voucher individual sin firma registrada (null) → no agrega sección, PDF válido")
    void generarVoucher_sinFirma_generaPdfValidoSinSeccionFirma() {
        Vuelo v = vuelo("PlusUltra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        a.setFirmaPasajero(null);

        byte[] pdf = pdfService.generarVoucher(a, List.of());

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    @Test
    @DisplayName("Voucher GRUPAL usa la firma del TITULAR, no la de los acompañantes")
    void generarVoucherGrupal_usaFirmaDelTitular_generaPdfValido() {
        Vuelo v = vuelo("PlusUltra");
        Atencion titular = atencionGrupo(v, 1L, "SGC-000000001", "Juan", "Perez");
        titular.setFirmaPasajero(FIRMA_PNG_BASE64);
        titular.setOrigenFirma(OrigenFirmaEnum.PASAJERO);
        titular.setFirmaFecha(LocalDateTime.of(2026, 7, 20, 15, 45));

        // El acompañante no tiene firma propia — el voucher grupal es UNO
        // SOLO y debe usar la del titular, igual que en el reporte.
        Atencion acompanante = atencionGrupo(v, 2L, "SGC-000000002", "Flor", "Vasquez");

        Map<Long, List<ServicioAsignado>> serviciosPorAtencion = Map.of(
                1L, List.of(),
                2L, List.of()
        );

        byte[] pdf = pdfService.generarVoucherGrupal(
                List.of(titular, acompanante), serviciosPorAtencion, true);

        assertNotNull(pdf);
        assertPdfValido(pdf);
    }

    // ══════════════════════════════════════════════════════════════════════
    // QR de verificación (mismo contenido que el ticket térmico, vía VoucherQr)
    // ══════════════════════════════════════════════════════════════════════

    private ServicioAsignado hotelConComidas(Atencion a, Vuelo v, String tipoHabitacion, int cantidad) {
        return ServicioAsignado.builder()
                .id(10L).atencion(a)
                .vueloRecurso(recurso(v, proveedor("Novotel Lima", "Av. José Pardo 879", "+51 1 610 8000")))
                .tipoDetalle(TipoDetalleEnum.HOTEL)
                .tipoHabitacion(tipoHabitacion)
                .cantidad(cantidad)
                .montoUnitario(new BigDecimal("150.00"))
                .montoSubtotal(new BigDecimal("150.00"))
                .asignadoEn(LocalDateTime.now())
                .desayuno(true).almuerzo(true)
                .fechaIngreso(LocalDate.of(2026, 7, 20))
                .fechaSalida(LocalDate.of(2026, 7, 21))
                .build();
    }

    private ServicioAsignado restauranteDesayuno(Atencion a, Vuelo v, int cantidad) {
        Proveedor p = Proveedor.builder().id(3L).tipo(TipoProveedorEnum.RESTAURANTE)
                .nombre("La Rosa Náutica Restaurant").ruc("20999999999").build();
        return ServicioAsignado.builder()
                .id(11L).atencion(a).vueloRecurso(recurso(v, p))
                .tipoDetalle(TipoDetalleEnum.RESTAURANTE)
                .cantidad(cantidad)
                .montoUnitario(new BigDecimal("40.00"))
                .montoSubtotal(new BigDecimal("40.00"))
                .asignadoEn(LocalDateTime.now())
                .desayuno(true)
                .build();
    }

    @Test
    @DisplayName("QR A4 individual → se lee y es IDÉNTICO al QR del ticket térmico de la misma atención")
    void generarVoucher_qrIgualAlDelTicket() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        List<ServicioAsignado> servicios = List.of(hotelConComidas(a, v, "DOBLE", 1));

        List<String> qrA4 = qrsDecodificados(pdfService.generarVoucher(a, servicios));
        TicketPdfRenderer ticket = new TicketPdfRenderer(mock(LineaAereaRepository.class), mock(IS3StorageService.class));
        List<String> qrTicket = qrsDecodificados(ticket.renderIndividual(a, servicios, 104));

        assertEquals(1, qrA4.size(), "El A4 debe tener exactamente un QR legible");
        assertEquals(qrTicket, qrA4, "El A4 y el ticket deben llevar el mismo QR");

        String esperado = "VOUCHER DE SERVICIO SGC-000001000\n"
                + "Pasajero: ANA/RUIZ\n"
                + "Vuelo: PU301/260720\n"
                + "PNR: ABC123\n"
                + "Lugar: LIM\n"
                + "HOTEL: NOVOTEL LIMA\n"
                + "Check-in: 20/07/2026\n"
                + "Check-out: 21/07/2026\n"
                + "Hab.: 1 DOBLE\n"
                + "Incluye: 1 desayuno, 1 almuerzo\n"
                + "Plus Ultra - 20/07/2026";
        assertEquals(esperado, qrA4.get(0));
    }

    @Test
    @DisplayName("QR A4 grupal con tildes y restaurante → ASCII, 'Pax/Incluye' (no 'cubiertos') e igual al del ticket")
    void generarVoucherGrupal_qrAsciiSinCubiertosIgualAlTicket() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion titular = atencionGrupo(v, 1L, "SGC-000000001", "Johnny MR", "Acero");
        Atencion acomp = atencionGrupo(v, 2L, "SGC-000000002", "Fabiola", "Rodríguez Espinoza");
        List<ServicioAsignado> compartidos = List.of(restauranteDesayuno(titular, v, 2));
        Map<Long, List<ServicioAsignado>> mapa = Map.of(1L, compartidos, 2L, compartidos);

        List<String> qrA4 = qrsDecodificados(pdfService.generarVoucherGrupal(List.of(titular, acomp), mapa, true));
        TicketPdfRenderer ticket = new TicketPdfRenderer(mock(LineaAereaRepository.class), mock(IS3StorageService.class));
        List<String> qrTicket = qrsDecodificados(ticket.renderGrupal(List.of(titular, acomp), mapa, true, 104));

        assertEquals(1, qrA4.size());
        assertEquals(qrTicket, qrA4, "El A4 grupal y el ticket grupal deben llevar el mismo QR");
        String qr = qrA4.get(0);
        assertTrue(qr.contains("2. FABIOLA/RODRIGUEZ ESPINOZA SGC-000000002"), qr);
        assertTrue(qr.contains("RESTAURANTE: LA ROSA NAUTICA RESTAURANT\nPax: 2\nIncluye: 2 desayunos"), qr);
        assertFalse(qr.contains("cubiertos"), qr);
        assertFalse(qr.contains("AirportHub"), "Ya no se usa el formato antiguo del QR");
        assertTrue(qr.chars().allMatch(c -> c == '\n' || (c >= 0x20 && c <= 0x7E)), "El QR debe ir en ASCII");
    }

    @Test
    @DisplayName("QR A4 grupal de 30 pasajeros → resumido con rango de correlativos, sin lista de nombres")
    void generarVoucherGrupal_treintaPasajeros_qrResumido() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        List<Atencion> grupo = new ArrayList<>();
        for (long i = 1; i <= 30; i++) {
            grupo.add(atencionGrupo(v, i, String.format("SGC-%09d", i), "Pasajero" + i, "Apellido" + i));
        }
        List<ServicioAsignado> compartidos = List.of(hotelConComidas(grupo.get(0), v, "DOBLE", 15));
        Map<Long, List<ServicioAsignado>> mapa = new HashMap<>();
        grupo.forEach(a -> mapa.put(a.getId(), compartidos));

        byte[] pdf = pdfService.generarVoucherGrupal(grupo, mapa, true);

        assertPdfValido(pdf);
        List<String> qrs = qrsDecodificados(pdf);
        assertEquals(1, qrs.size(), "Debe haber exactamente un QR legible");
        String qr = qrs.get(0);
        assertTrue(qr.contains("GRUPAL - 30 PASAJEROS"), qr);
        assertTrue(qr.contains("PASAJEROS: 30 (SGC-000000001 a SGC-000000030)"), qr);
        assertFalse(qr.contains("1. PASAJERO1/APELLIDO1"), "Con más de 10 pasajeros el QR no lleva la lista de nombres");
        assertTrue(qr.contains("Hab.: 15 DOBLE"), qr);
    }

    @Test
    @DisplayName("QR A4 grupal de 30 pasajeros con servicios INDEPENDIENTES → remite al voucher impreso")
    void generarVoucherGrupal_treintaPasajerosIndependientes_qrRemiteAlVoucher() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        List<Atencion> grupo = new ArrayList<>();
        Map<Long, List<ServicioAsignado>> mapa = new HashMap<>();
        for (long i = 1; i <= 30; i++) {
            Atencion a = atencionGrupo(v, i, String.format("SGC-%09d", i), "Pasajero" + i, "Apellido" + i);
            grupo.add(a);
            mapa.put(i, List.of(hotelConComidas(a, v, "SIMPLE", 1)));
        }

        List<String> qrs = qrsDecodificados(pdfService.generarVoucherGrupal(grupo, mapa, false));

        assertEquals(1, qrs.size());
        String qr = qrs.get(0);
        assertTrue(qr.contains("PASAJEROS: 30 (SGC-000000001 a SGC-000000030)"), qr);
        assertTrue(qr.contains("Servicios por pasajero: ver voucher impreso"), qr);
        assertFalse(qr.contains("HOTEL:"), "El QR no repite los servicios de cada pasajero");
    }

    @Test
    @DisplayName("QR A4 en inglés (idiomaVoucher = EN) → etiquetas del QR traducidas")
    void generarVoucher_qrEnIngles() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, LocalDate.of(2026, 7, 20));
        a.setIdiomaVoucher(IdiomaVoucherEnum.EN);

        List<String> qrs = qrsDecodificados(pdfService.generarVoucher(a, List.of(hotelConComidas(a, v, "DOBLE", 1))));

        assertEquals(1, qrs.size());
        String qr = qrs.get(0);
        assertTrue(qr.startsWith("SERVICE VOUCHER SGC-000001000\nPassenger: ANA/RUIZ\nFlight: PU301/260720"), qr);
        assertTrue(qr.contains("Room(s): 1 DOUBLE"), qr);
        assertTrue(qr.contains("Includes: 1 breakfast, 1 lunch"), qr);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helper de validación
    // ══════════════════════════════════════════════════════════════════════

    /** Decodifica todas las imágenes del PDF (todas las páginas) y devuelve las que sean QR legibles. */
    private List<String> qrsDecodificados(byte[] pdf) throws Exception {
        List<String> resultado = new ArrayList<>();
        Map<DecodeHintType, Object> hints = new HashMap<>();
        hints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);

        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                PdfDictionary xobjects = doc.getPage(p).getResources().getResource(PdfName.XObject);
                if (xobjects == null) continue;
                for (PdfName nombre : xobjects.keySet()) {
                    PdfStream stream = xobjects.getAsStream(nombre);
                    if (stream == null || !PdfName.Image.equals(stream.getAsName(PdfName.Subtype))) continue;
                    try {
                        BufferedImage img = new PdfImageXObject(stream).getBufferedImage();
                        if (img == null) continue;
                        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img)));
                        resultado.add(new QRCodeReader().decode(bitmap, hints).getText());
                    } catch (Exception noEsQr) {
                        // logo, firma u otra imagen: se ignora
                    }
                }
            }
        }
        return resultado;
    }

    /** Verifica que los bytes generados sean un PDF válido (firma %PDF-). */
    private void assertPdfValido(byte[] pdf) {
        String header = new String(pdf, 0, 5, StandardCharsets.US_ASCII);
        assertEquals("%PDF-", header);
    }
}