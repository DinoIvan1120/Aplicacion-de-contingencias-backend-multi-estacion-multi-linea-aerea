package com.saasa.contingencias.service.impl;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDictionary;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfStream;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject;
import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;
import com.saasa.contingencias.domain.enumeration.OrigenFirmaEnum;
import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.model.LineaAerea;
import com.saasa.contingencias.domain.model.Proveedor;
import com.saasa.contingencias.domain.model.ServicioAsignado;
import com.saasa.contingencias.domain.model.Vuelo;
import com.saasa.contingencias.domain.model.VueloRecurso;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import com.saasa.contingencias.service.IS3StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test unitario de TicketPdfRenderer (voucher tipo ticket 80/104 mm).
 *
 * Igual que PdfGeneratorServiceImplTest, no usa base de datos: arma el PDF
 * en memoria a partir de entidades de dominio. Además de validar la firma
 * %PDF-, abre el PDF generado con iText para comprobar lo propio del
 * ticket: una sola página, ancho exacto del rollo, alto recortado al
 * contenido, textos esperados y QR legible con el MISMO contenido que el
 * QR del voucher A4.
 */
class TicketPdfRendererTest {

    private static final float PT_POR_MM = 72f / 25.4f;
    private static final float ALTO_INICIAL_PT = 14000f;

    private LineaAereaRepository lineaAereaRepository;
    private IS3StorageService s3StorageService;
    private TicketPdfRenderer renderer;

    @BeforeEach
    void setUp() {
        lineaAereaRepository = mock(LineaAereaRepository.class);
        s3StorageService = mock(IS3StorageService.class);
        renderer = new TicketPdfRenderer(lineaAereaRepository, s3StorageService);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Fixtures
    // ══════════════════════════════════════════════════════════════════════

    private Vuelo vuelo(String aerolinea) {
        return Vuelo.builder()
                .id(1L)
                .aerolinea(aerolinea)
                .codigoVuelo("PU301")
                .origen("LIM")
                .destino("MAD")
                .fechaVuelo(LocalDate.of(2026, 10, 20))
                .build();
    }

    private Atencion atencion(Vuelo vuelo, Long id, String correlativo, String nombre, String apellido) {
        return Atencion.builder()
                .id(id)
                .numeroCorrelativo(correlativo)
                .vuelo(vuelo)
                .nombre(nombre)
                .apellido(apellido)
                .pnr("ABC123")
                .fechaEmision(LocalDate.of(2026, 10, 20))
                .lugarEmision("LIM")
                .correo(nombre.toLowerCase() + "@test.com")
                .build();
    }

    private Proveedor proveedor(TipoProveedorEnum tipo, String nombre, String direccion, String telefono) {
        return Proveedor.builder()
                .id(1L)
                .tipo(tipo)
                .nombre(nombre)
                .ruc("20123456789")
                .direccion(direccion)
                .telefono(telefono)
                .build();
    }

    private ServicioAsignado hotel(Atencion a, Vuelo v, String tipoHabitacion, int cantidad) {
        VueloRecurso r = VueloRecurso.builder().id(1L).vuelo(v)
                .proveedor(proveedor(TipoProveedorEnum.HOTEL, "Novotel Lima",
                        "Av. José Pardo 879, Miraflores, Lima", "+51 1 610 8000"))
                .build();
        return ServicioAsignado.builder()
                .id(1L).atencion(a).vueloRecurso(r)
                .tipoDetalle(TipoDetalleEnum.HOTEL)
                .tipoHabitacion(tipoHabitacion)
                .cantidad(cantidad)
                .montoUnitario(new BigDecimal("150.00"))
                .montoSubtotal(new BigDecimal("150.00"))
                .asignadoEn(LocalDateTime.now())
                .desayuno(true).almuerzo(true).snack(true).cena(true)
                .fechaIngreso(LocalDate.of(2026, 10, 20))
                .fechaSalida(LocalDate.of(2026, 10, 21))
                .build();
    }

    private ServicioAsignado transporte(Atencion a, Vuelo v, int cantidad) {
        VueloRecurso r = VueloRecurso.builder().id(2L).vuelo(v)
                .proveedor(proveedor(TipoProveedorEnum.TRANSPORTE, "Lima Airport Transfer VIP",
                        "Av. Tomás Marsano 1015, Surquillo, Lima", "+51 1 445 7890"))
                .build();
        return ServicioAsignado.builder()
                .id(2L).atencion(a).vueloRecurso(r)
                .tipoDetalle(TipoDetalleEnum.TRANSPORTE)
                .tipoTransporte("AMBOS")
                .cantidad(cantidad)
                .montoUnitario(new BigDecimal("50.00"))
                .montoSubtotal(new BigDecimal("50.00"))
                .asignadoEn(LocalDateTime.now())
                .build();
    }

    private ServicioAsignado restaurante(Atencion a, Vuelo v, int cantidad) {
        VueloRecurso r = VueloRecurso.builder().id(3L).vuelo(v)
                .proveedor(proveedor(TipoProveedorEnum.RESTAURANTE, "La Rosa Náutica Restaurant", null, null))
                .build();
        return ServicioAsignado.builder()
                .id(3L).atencion(a).vueloRecurso(r)
                .tipoDetalle(TipoDetalleEnum.RESTAURANTE)
                .cantidad(cantidad)
                .montoUnitario(new BigDecimal("40.00"))
                .montoSubtotal(new BigDecimal("40.00"))
                .asignadoEn(LocalDateTime.now())
                .desayuno(true)
                .build();
    }

    /** Firma realista: PNG con un trazo, en data URL base64 (como la guarda el canvas del agente). */
    private String firmaDataUrl() throws Exception {
        BufferedImage img = new BufferedImage(260, 90, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(3f));
        g.drawLine(10, 70, 120, 15);
        g.drawLine(120, 15, 250, 60);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private byte[] pngColor(int ancho, int alto) throws Exception {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(220, 30, 30));
        g.fillRect(0, 0, ancho, alto);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Ticket INDIVIDUAL
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Individual 80 mm → una sola página, ancho 80 mm y alto recortado al contenido")
    void renderIndividual_80mm_unaPaginaAnchoExactoYAltoRecortado() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, 1L, "SGC-LIM-PU-000001", "Jovita MS", "Campos");

        byte[] pdf = renderer.renderIndividual(a,
                List.of(hotel(a, v, "SIMPLE", 1), transporte(a, v, 1), restaurante(a, v, 1)), 80);

        assertPdfValido(pdf);
        try (PdfDocument doc = abrir(pdf)) {
            assertEquals(1, doc.getNumberOfPages(), "El ticket debe salir en UNA sola página");
            Rectangle caja = doc.getPage(1).getMediaBox();
            assertEquals(80 * PT_POR_MM, caja.getWidth(), 0.5f);
            assertTrue(caja.getHeight() < ALTO_INICIAL_PT, "El alto debe recortarse al contenido");
            assertTrue(caja.getHeight() > 100 * PT_POR_MM, "Con 3 servicios el ticket mide más de 10 cm");
        }
    }

    @Test
    @DisplayName("Individual 104 mm → ancho de rollo de 4\"")
    void renderIndividual_104mm_anchoDeCuatroPulgadas() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, 1L, "SGC-LIM-PU-000001", "Ana", "Ruiz");

        byte[] pdf = renderer.renderIndividual(a, List.of(hotel(a, v, "SIMPLE", 1)), 104);

        try (PdfDocument doc = abrir(pdf)) {
            assertEquals(1, doc.getNumberOfPages());
            assertEquals(104 * PT_POR_MM, doc.getPage(1).getMediaBox().getWidth(), 0.5f);
        }
    }

    @Test
    @DisplayName("Individual → incluye datos del pasajero, vuelo y bloques de hotel/transporte/restaurante")
    void renderIndividual_incluyeDatosYServicios() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, 1L, "SGC-LIM-PU-000001", "Jovita MS", "Campos");

        String texto = texto(renderer.renderIndividual(a,
                List.of(hotel(a, v, "SIMPLE", 1), transporte(a, v, 1), restaurante(a, v, 1)), 80));

        assertTrue(texto.contains("VOUCHER DE SERVICIO"));
        assertTrue(texto.contains("SGC-LIM-PU-000001"));
        assertTrue(texto.contains("JOVITA MS/CAMPOS"));
        assertTrue(texto.contains("PU301/261020"));
        assertTrue(texto.contains("ABC123"));
        assertTrue(texto.contains("NOVOTEL LIMA"));
        assertTrue(texto.contains("20/10/2026"));
        // El texto puede partirse en varias líneas al ancho del rollo: se valida por partes
        assertTrue(texto.contains("1 desayuno"));
        assertTrue(texto.contains("1 cena"));
        assertTrue(texto.contains("LIMA AIRPORT TRANSFER"));
        assertTrue(texto.contains("+51 1 445 7890"));
        assertTrue(texto.contains("ROSA"));
        assertFalse(texto.contains("GRUPAL"), "El individual no debe decir GRUPAL");
    }

    @Test
    @DisplayName("Individual → el QR se lee y tiene el MISMO contenido que el QR del voucher A4")
    void renderIndividual_qrLegibleConContenidoDelA4() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, 1L, "SGC-LIM-PU-000001", "Ana", "Ruiz");

        byte[] pdf = renderer.renderIndividual(a, List.of(hotel(a, v, "SIMPLE", 1)), 80);

        String esperado = "VOUCHER: SGC-LIM-PU-000001\n"
                + "PASAJERO: Ana/Ruiz\n"
                + "VUELO: PU301/261020\n"
                + "PNR: ABC123\n"
                + "HOTEL: Novotel Lima (1xSIMPLE)\n"
                + "EMITIDO: LIM\n"
                + "AirportHub - Plus Ultra";
        assertTrue(qrsDecodificados(pdf).contains(esperado), "El QR del ticket debe coincidir con el del A4");
    }

    @Test
    @DisplayName("Individual en inglés (idiomaVoucher = EN) → etiquetas traducidas")
    void renderIndividual_idiomaIngles_traduceEtiquetas() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, 1L, "SGC-LIM-PU-000001", "Ana", "Ruiz");
        a.setIdiomaVoucher(IdiomaVoucherEnum.EN);

        String texto = texto(renderer.renderIndividual(a, List.of(hotel(a, v, "DOBLE", 1)), 80));

        assertTrue(texto.contains("SERVICE VOUCHER"));
        assertTrue(texto.contains("Passenger:"));
        assertTrue(texto.contains("DOUBLE"));
        assertTrue(texto.contains("VERIFICATION CODE"));
        assertFalse(texto.contains("VOUCHER DE SERVICIO"));
    }

    @Test
    @DisplayName("Individual con firma del PASAJERO → se imprime la firma con nombre y fecha")
    void renderIndividual_firmaPasajero_seImprime() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, 1L, "SGC-LIM-PU-000001", "Ana", "Ruiz");
        a.setFirmaPasajero(firmaDataUrl());
        a.setOrigenFirma(OrigenFirmaEnum.PASAJERO);
        a.setFirmaFecha(LocalDateTime.of(2026, 10, 20, 21, 10));

        byte[] pdf = renderer.renderIndividual(a, List.of(hotel(a, v, "SIMPLE", 1)), 80);
        String texto = texto(pdf);

        assertTrue(texto.contains("Firma de conformidad:"));
        assertTrue(texto.contains("ANA RUIZ"));
        assertTrue(texto.contains("Firmado el 20/10/2026 21:10"));
    }

    @Test
    @DisplayName("Individual de carga masiva (origenFirma = AGENTE_LOTE) → NO imprime la firma")
    void renderIndividual_firmaAgenteLote_noSeImprime() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, 1L, "SGC-LIM-PU-000001", "Ana", "Ruiz");
        a.setFirmaPasajero("Carlos Agente");
        a.setOrigenFirma(OrigenFirmaEnum.AGENTE_LOTE);
        a.setFirmaFecha(LocalDateTime.of(2026, 10, 20, 21, 10));

        String texto = texto(renderer.renderIndividual(a, List.of(hotel(a, v, "SIMPLE", 1)), 80));

        assertFalse(texto.contains("Firma de conformidad"));
        assertFalse(texto.contains("Carlos Agente"));
    }

    @Test
    @DisplayName("Individual con firma base64 corrupta → no revienta y muestra '(Firma no disponible)'")
    void renderIndividual_firmaCorrupta_noLanzaExcepcion() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, 1L, "SGC-LIM-PU-000001", "Ana", "Ruiz");
        a.setFirmaPasajero("data:image/png;base64,@@no-es-base64@@");
        a.setOrigenFirma(OrigenFirmaEnum.PASAJERO);

        byte[] pdf = renderer.renderIndividual(a, List.of(), 80);

        assertPdfValido(pdf);
        assertTrue(texto(pdf).contains("(Firma no disponible)"));
    }

    @Test
    @DisplayName("Individual sin servicios y con campos nulos → PDF válido con 'Sin servicios asignados'")
    void renderIndividual_sinServiciosYCamposNulos_noLanzaExcepcion() throws Exception {
        Vuelo v = vuelo(null);
        Atencion a = Atencion.builder()
                .id(1L).numeroCorrelativo("SGC-LIM-PU-000009").vuelo(v)
                .nombre("Ana").apellido(null).pnr("ABC123")
                .build(); // sin fechaEmision, sin lugarEmision

        byte[] pdf = renderer.renderIndividual(a, List.of(), 80);

        assertPdfValido(pdf);
        String texto = texto(pdf);
        assertTrue(texto.contains("Sin servicios asignados"));
        assertTrue(texto.contains("PLUS ULTRA"), "Sin aerolínea, el pie usa PLUS ULTRA como el A4");
    }

    @Test
    @DisplayName("Servicio sin proveedor (vueloRecurso nulo) → usa 'PROVEEDOR NO ESPECIFICADO'")
    void renderIndividual_servicioSinProveedor_noLanzaExcepcion() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, 1L, "SGC-LIM-PU-000001", "Ana", "Ruiz");
        ServicioAsignado sinProveedor = ServicioAsignado.builder()
                .id(9L).atencion(a).vueloRecurso(null)
                .tipoDetalle(TipoDetalleEnum.HOTEL)
                .tipoHabitacion(null)
                .cantidad(null)
                .montoUnitario(BigDecimal.ZERO).montoSubtotal(BigDecimal.ZERO)
                .asignadoEn(LocalDateTime.now())
                .build();

        byte[] pdf = renderer.renderIndividual(a, List.of(sinProveedor), 80);

        assertPdfValido(pdf);
        assertTrue(texto(pdf).contains("PROVEEDOR NO ESPECIFICADO"));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Ticket GRUPAL
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Grupal con servicios COMPARTIDOS → lista de pasajeros y un solo bloque de servicios")
    void renderGrupal_serviciosCompartidos_listaPasajerosYUnSoloBloque() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion titular = atencion(v, 1L, "SGC-LIM-PU-000002", "Johnny MR", "Acero");
        Atencion acomp1 = atencion(v, 2L, "SGC-LIM-PU-000003", "Fabiola", "Rodríguez Espinoza");
        Atencion acomp2 = atencion(v, 3L, "SGC-LIM-PU-000004", "Cecilia", "Castillo Aguirre");
        List<ServicioAsignado> compartidos = List.of(hotel(titular, v, "DOBLE", 2), transporte(titular, v, 3));
        Map<Long, List<ServicioAsignado>> mapa = Map.of(1L, compartidos, 2L, compartidos, 3L, compartidos);

        byte[] pdf = renderer.renderGrupal(List.of(titular, acomp1, acomp2), mapa, true, 80);

        assertPdfValido(pdf);
        try (PdfDocument doc = abrir(pdf)) {
            assertEquals(1, doc.getNumberOfPages());
            assertEquals(80 * PT_POR_MM, doc.getPage(1).getMediaBox().getWidth(), 0.5f);
        }
        String texto = texto(pdf);
        assertTrue(texto.contains("3 PASAJEROS"));
        assertTrue(texto.contains("JOHNNY MR/ACERO"));
        assertTrue(texto.contains("FABIOLA/RODRÍGUEZ ESPINOZA"));
        assertTrue(texto.contains("CECILIA/CASTILLO AGUIRRE"));
        assertTrue(texto.contains("SGC-LIM-PU-000003"));
        assertTrue(texto.contains("SGC-LIM-PU-000004"));
        assertEquals(1, ocurrencias(texto, "HOTEL: NOVOTEL LIMA"), "Compartidos: un solo bloque de hotel");
        assertFalse(texto.contains("PASAJERO 1/3"), "Compartidos: sin secciones por pasajero");
    }

    @Test
    @DisplayName("Grupal con servicios INDEPENDIENTES → una sección por pasajero con sus servicios")
    void renderGrupal_serviciosIndependientes_seccionPorPasajero() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion p1 = atencion(v, 1L, "SGC-LIM-PU-000002", "Juan", "Perez");
        Atencion p2 = atencion(v, 2L, "SGC-LIM-PU-000003", "Flor", "Vasquez");
        Map<Long, List<ServicioAsignado>> mapa = Map.of(
                1L, List.of(hotel(p1, v, "SIMPLE", 1)),
                2L, List.of(restaurante(p2, v, 1)));

        String texto = texto(renderer.renderGrupal(List.of(p1, p2), mapa, false, 80));

        assertTrue(texto.contains("PASAJERO 1/2"));
        assertTrue(texto.contains("PASAJERO 2/2"));
        assertEquals(1, ocurrencias(texto, "HOTEL: NOVOTEL LIMA"));
        assertTrue(texto.contains("ROSA"));
    }

    @Test
    @DisplayName("Grupal independiente con un pasajero sin servicios → no revienta")
    void renderGrupal_pasajeroSinServicios_noLanzaExcepcion() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion p1 = atencion(v, 1L, "SGC-LIM-PU-000002", "Juan", "Perez");
        Atencion p2 = atencion(v, 2L, "SGC-LIM-PU-000003", "Flor", "Vasquez");
        Map<Long, List<ServicioAsignado>> mapa = Map.of(1L, List.of(hotel(p1, v, "SIMPLE", 1)));
        // p2 no tiene entrada en el mapa → getOrDefault(..., List.of())

        byte[] pdf = renderer.renderGrupal(List.of(p1, p2), mapa, false, 104);

        assertPdfValido(pdf);
        assertTrue(texto(pdf).contains("Sin servicios asignados"));
    }

    @Test
    @DisplayName("Grupal → el QR se lee y tiene el MISMO contenido que el QR del A4 grupal")
    void renderGrupal_qrLegibleConContenidoDelA4() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion titular = atencion(v, 1L, "SGC-LIM-PU-000002", "Juan", "Perez");
        Atencion acomp = atencion(v, 2L, "SGC-LIM-PU-000003", "Flor", "Vasquez");
        List<ServicioAsignado> compartidos = List.of(hotel(titular, v, "DOBLE", 1));

        byte[] pdf = renderer.renderGrupal(List.of(titular, acomp),
                Map.of(1L, compartidos, 2L, compartidos), true, 80);

        String esperado = "VOUCHER GRUPAL: SGC-LIM-PU-000002\n"
                + "PASAJEROS (2): Juan/Perez, Flor/Vasquez\n"
                + "VUELO: PU301/261020\n"
                + "PNR: ABC123\n"
                + "HOTEL: Novotel Lima (1xDOBLE)\n"
                + "EMITIDO: LIM\n"
                + "AirportHub - Plus Ultra";
        assertTrue(qrsDecodificados(pdf).contains(esperado), "El QR grupal debe coincidir con el del A4");
    }

    @Test
    @DisplayName("Grupal → imprime la firma del TITULAR, no la de los acompañantes")
    void renderGrupal_firmaDelTitular() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion titular = atencion(v, 1L, "SGC-LIM-PU-000002", "Johnny MR", "Acero");
        titular.setFirmaPasajero(firmaDataUrl());
        titular.setOrigenFirma(OrigenFirmaEnum.PASAJERO);
        titular.setFirmaFecha(LocalDateTime.of(2026, 10, 20, 21, 16));
        Atencion acomp = atencion(v, 2L, "SGC-LIM-PU-000003", "Flor", "Vasquez");
        acomp.setFirmaPasajero(firmaDataUrl());
        acomp.setOrigenFirma(OrigenFirmaEnum.PASAJERO);

        String texto = texto(renderer.renderGrupal(List.of(titular, acomp),
                Map.of(1L, List.of(), 2L, List.of()), true, 80));

        assertEquals(1, ocurrencias(texto, "Firma de conformidad:"));
        assertTrue(texto.contains("JOHNNY MR ACERO"));
        assertTrue(texto.contains("Firmado el 20/10/2026 21:16"));
    }

    @Test
    @DisplayName("Grupal en inglés (idioma del titular) → GROUP y PASSENGERS")
    void renderGrupal_idiomaInglesDelTitular() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion titular = atencion(v, 1L, "SGC-LIM-PU-000002", "Juan", "Perez");
        titular.setIdiomaVoucher(IdiomaVoucherEnum.EN);
        Atencion acomp = atencion(v, 2L, "SGC-LIM-PU-000003", "Flor", "Vasquez"); // ES, se ignora

        String texto = texto(renderer.renderGrupal(List.of(titular, acomp),
                Map.of(1L, List.of(), 2L, List.of()), false, 80));

        assertTrue(texto.contains("SERVICE VOUCHER"));
        assertTrue(texto.contains("2 PASSENGERS"));
        assertTrue(texto.contains("PASSENGER 1/2"));
    }

    @Test
    @DisplayName("Grupal de 6 pasajeros (máximo real) con servicios COMPARTIDOS → una página y QR legible con el contenido del A4")
    void renderGrupal_seisPasajerosCompartidos_qrLegible() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        List<Atencion> grupo = new ArrayList<>();
        for (long i = 1; i <= 6; i++) {
            grupo.add(atencion(v, i, String.format("SGC-LIM-PU-%06d", i), "Pasajero" + i, "Apellido" + i));
        }
        Atencion titular = grupo.get(0);
        List<ServicioAsignado> compartidos = List.of(hotel(titular, v, "DOBLE", 3), transporte(titular, v, 6));
        Map<Long, List<ServicioAsignado>> mapa = new HashMap<>();
        for (Atencion a : grupo) {
            mapa.put(a.getId(), compartidos);
        }

        byte[] pdf = renderer.renderGrupal(grupo, mapa, true, 80);

        assertPdfValido(pdf);
        try (PdfDocument doc = abrir(pdf)) {
            assertEquals(1, doc.getNumberOfPages(), "Un grupo real debe salir en UNA sola página");
        }
        assertTrue(texto(pdf).contains("6 PASAJEROS"));

        String esperado = "VOUCHER GRUPAL: SGC-LIM-PU-000001\n"
                + "PASAJEROS (6): Pasajero1/Apellido1, Pasajero2/Apellido2, Pasajero3/Apellido3, "
                + "Pasajero4/Apellido4, Pasajero5/Apellido5, Pasajero6/Apellido6\n"
                + "VUELO: PU301/261020\n"
                + "PNR: ABC123\n"
                + "HOTEL: Novotel Lima (3xDOBLE)\n"
                + "TRANSPORTE: Lima Airport Transfer VIP (6 PAX - AEROPUERTO - DOMICILIO Y DOMICILIO - AEROPUERTO)\n"
                + "EMITIDO: LIM\n"
                + "AirportHub - Plus Ultra";
        assertTrue(qrsDecodificados(pdf).contains(esperado),
                "Con 6 pasajeros el QR debe generarse y coincidir con el del A4");
    }

    @Test
    @DisplayName("Grupal de 6 pasajeros con servicios INDEPENDIENTES → una página y QR presente y legible")
    void renderGrupal_seisPasajerosIndependientes_qrPresente() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        List<Atencion> grupo = new ArrayList<>();
        Map<Long, List<ServicioAsignado>> mapa = new HashMap<>();
        for (long i = 1; i <= 6; i++) {
            Atencion a = atencion(v, i, String.format("SGC-LIM-PU-%06d", i), "Pasajero" + i, "Apellido" + i);
            grupo.add(a);
            mapa.put(i, List.of(hotel(a, v, "SIMPLE", 1), transporte(a, v, 1)));
        }

        byte[] pdf = renderer.renderGrupal(grupo, mapa, false, 80);

        assertPdfValido(pdf);
        try (PdfDocument doc = abrir(pdf)) {
            assertEquals(1, doc.getNumberOfPages());
        }
        assertTrue(texto(pdf).contains("PASAJERO 6/6"));
        assertEquals(1, qrsDecodificados(pdf).size(), "Debe haber exactamente un QR legible");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Logo
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Logo dinámico → se descarga de S3 usando LineaAerea.logoKey")
    void logo_dinamico_seDescargaDeS3() throws Exception {
        Vuelo v = vuelo("LATAM");
        v.setLineaAereaId(7L);
        LineaAerea latam = LineaAerea.builder().id(7L).codigoIata("LA").nombre("LATAM")
                .logoKey("contingencias/logos/LA.png").build();
        when(lineaAereaRepository.findById(7L)).thenReturn(Optional.of(latam));
        when(s3StorageService.descargarPdf("contingencias/logos/LA.png")).thenReturn(pngColor(300, 100));
        Atencion a = atencion(v, 1L, "SGC-LIM-LA-000001", "Ana", "Ruiz");

        byte[] pdf = renderer.renderIndividual(a, List.of(), 80);

        assertPdfValido(pdf);
        verify(s3StorageService).descargarPdf("contingencias/logos/LA.png");
        assertEquals(2, contarImagenes(pdf), "Logo + QR");
    }

    @Test
    @DisplayName("Logo: si S3 falla → el ticket se genera igual, sin logo")
    void logo_falloS3_generaTicketSinLogo() throws Exception {
        Vuelo v = vuelo("LATAM");
        v.setLineaAereaId(7L);
        LineaAerea latam = LineaAerea.builder().id(7L).codigoIata("LA").nombre("LATAM")
                .logoKey("contingencias/logos/LA.png").build();
        when(lineaAereaRepository.findById(7L)).thenReturn(Optional.of(latam));
        when(s3StorageService.descargarPdf(anyString())).thenThrow(new RuntimeException("S3 caído"));
        Atencion a = atencion(v, 1L, "SGC-LIM-LA-000001", "Ana", "Ruiz");

        byte[] pdf = renderer.renderIndividual(a, List.of(), 80);

        assertPdfValido(pdf);
        assertEquals(1, contarImagenes(pdf), "Solo el QR");
    }

    @Test
    @DisplayName("Logo: Plus Ultra sin logo en S3 → usa el logo de respaldo del classpath")
    void logo_plusUltraSinS3_usaRespaldo() throws Exception {
        Vuelo v = vuelo("Plus Ultra"); // sin lineaAereaId
        Atencion a = atencion(v, 1L, "SGC-LIM-PU-000001", "Ana", "Ruiz");

        byte[] pdf = renderer.renderIndividual(a, List.of(), 80);

        verify(s3StorageService, never()).descargarPdf(anyString());
        assertEquals(2, contarImagenes(pdf), "Logo de respaldo + QR");
    }

    @Test
    @DisplayName("Logo: aerolínea sin logo y sin respaldo → encabezado sin imagen")
    void logo_aerolineaSinLogo_soloQr() throws Exception {
        Vuelo v = vuelo("Sky Airline");
        Atencion a = atencion(v, 1L, "SGC-LIM-H2-000001", "Ana", "Ruiz");

        byte[] pdf = renderer.renderIndividual(a, List.of(), 80);

        assertPdfValido(pdf);
        assertEquals(1, contarImagenes(pdf), "Solo el QR");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers de validación
    // ══════════════════════════════════════════════════════════════════════

    /** Verifica que los bytes generados sean un PDF válido (firma %PDF-). */
    private void assertPdfValido(byte[] pdf) {
        assertNotNull(pdf);
        assertTrue(pdf.length > 5);
        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII));
    }

    private PdfDocument abrir(byte[] pdf) throws Exception {
        return new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)));
    }

    /** Texto de todas las páginas del PDF. */
    private String texto(byte[] pdf) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (PdfDocument doc = abrir(pdf)) {
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                sb.append(PdfTextExtractor.getTextFromPage(doc.getPage(i))).append("\n");
            }
        }
        return sb.toString();
    }

    private int ocurrencias(String texto, String buscado) {
        int n = 0;
        int i = texto.indexOf(buscado);
        while (i >= 0) {
            n++;
            i = texto.indexOf(buscado, i + buscado.length());
        }
        return n;
    }

    /** Cantidad de imágenes (XObject /Image) en la página 1. */
    private int contarImagenes(byte[] pdf) throws Exception {
        try (PdfDocument doc = abrir(pdf)) {
            PdfDictionary xobjects = doc.getPage(1).getResources().getResource(PdfName.XObject);
            if (xobjects == null) return 0;
            int n = 0;
            for (PdfName nombre : xobjects.keySet()) {
                PdfStream stream = xobjects.getAsStream(nombre);
                if (stream != null && PdfName.Image.equals(stream.getAsName(PdfName.Subtype))) n++;
            }
            return n;
        }
    }

    /** Extrae las imágenes de la página 1 y devuelve el texto de las que sean QR legibles. */
    private List<String> qrsDecodificados(byte[] pdf) throws Exception {
        List<String> resultado = new ArrayList<>();
        Map<DecodeHintType, Object> hints = new HashMap<>();
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);

        try (PdfDocument doc = abrir(pdf)) {
            PdfDictionary xobjects = doc.getPage(1).getResources().getResource(PdfName.XObject);
            if (xobjects == null) return resultado;
            for (PdfName nombre : xobjects.keySet()) {
                PdfStream stream = xobjects.getAsStream(nombre);
                if (stream == null || !PdfName.Image.equals(stream.getAsName(PdfName.Subtype))) continue;
                BufferedImage img = new PdfImageXObject(stream).getBufferedImage();
                if (img == null) continue;
                try {
                    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img)));
                    Result r = new QRCodeReader().decode(bitmap, hints);
                    resultado.add(r.getText());
                } catch (Exception noEsQr) {
                    // logo u otra imagen: se ignora
                }
            }
        }
        return resultado;
    }
}