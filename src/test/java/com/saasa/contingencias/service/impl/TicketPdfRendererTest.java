package com.saasa.contingencias.service.impl;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.itextpdf.kernel.geom.Matrix;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDictionary;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfStream;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData;
import com.itextpdf.kernel.pdf.canvas.parser.data.ImageRenderInfo;
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo;
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener;
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
import java.util.Set;

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
 * contenido, textos esperados y QR legible con el MISMO contenido que se
 * imprime en el ticket (en ASCII, sin tildes).
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

    /** Grupo de n pasajeros con correlativos SGC-LIM-PU-000001 … y nombres PasajeroN/ApellidoN. */
    private List<Atencion> grupo(Vuelo v, int n) {
        List<Atencion> grupo = new ArrayList<>();
        for (long i = 1; i <= n; i++) {
            grupo.add(atencion(v, i, String.format("SGC-LIM-PU-%06d", i), "Pasajero" + i, "Apellido" + i));
        }
        return grupo;
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
    @DisplayName("Individual → el QR se lee y tiene el MISMO contenido que se imprime en el ticket")
    void renderIndividual_qrLegibleConContenidoDelTicket() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, 1L, "SGC-LIM-PU-000001", "Ana", "Ruiz");

        byte[] pdf = renderer.renderIndividual(a, List.of(hotel(a, v, "SIMPLE", 1)), 80);

        String esperado = "VOUCHER DE SERVICIO SGC-LIM-PU-000001\n"
                + "Pasajero: ANA/RUIZ\n"
                + "Vuelo: PU301/261020\n"
                + "PNR: ABC123\n"
                + "Lugar: LIM\n"
                + "HOTEL: NOVOTEL LIMA\n"
                + "Check-in: 20/10/2026\n"
                + "Check-out: 21/10/2026\n"
                + "Hab.: 1 SIMPLE\n"
                + "Incluye: 1 desayuno, 1 almuerzo, 1 snack, 1 cena\n"
                + "Plus Ultra - 20/10/2026";
        assertEquals(List.of(esperado), qrsDecodificados(pdf), "El QR debe repetir lo impreso en el ticket");
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
    @DisplayName("Grupal → el QR se lee y tiene el MISMO contenido que se imprime en el ticket")
    void renderGrupal_qrLegibleConContenidoDelTicket() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion titular = atencion(v, 1L, "SGC-LIM-PU-000002", "Juan", "Perez");
        Atencion acomp = atencion(v, 2L, "SGC-LIM-PU-000003", "Flor", "Vasquez");
        List<ServicioAsignado> compartidos = List.of(hotel(titular, v, "DOBLE", 1));

        byte[] pdf = renderer.renderGrupal(List.of(titular, acomp),
                Map.of(1L, compartidos, 2L, compartidos), true, 80);

        String esperado = "VOUCHER DE SERVICIO SGC-LIM-PU-000002\n"
                + "GRUPAL - 2 PASAJEROS\n"
                + "Vuelo: PU301/261020\n"
                + "PNR: ABC123\n"
                + "Lugar: LIM\n"
                + "PASAJEROS (2):\n"
                + "1. JUAN/PEREZ SGC-LIM-PU-000002\n"
                + "2. FLOR/VASQUEZ SGC-LIM-PU-000003\n"
                + "HOTEL: NOVOTEL LIMA\n"
                + "Check-in: 20/10/2026\n"
                + "Check-out: 21/10/2026\n"
                + "Hab.: 1 DOBLE\n"
                + "Incluye: 1 desayuno, 1 almuerzo, 1 snack, 1 cena\n"
                + "Plus Ultra - 20/10/2026";
        assertEquals(List.of(esperado), qrsDecodificados(pdf), "El QR grupal debe repetir lo impreso en el ticket");
    }

    @Test
    @DisplayName("Grupal con restaurante y tildes → el QR dice Pax e Incluye (no 'cubiertos') y va en ASCII")
    void renderGrupal_qrRestauranteComoTicket_sinCubiertosYEnAscii() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion titular = atencion(v, 1L, "SGC-LIM-PU-000002", "Johnny MR", "Acero");
        Atencion acomp = atencion(v, 2L, "SGC-LIM-PU-000003", "Fabiola", "Rodríguez Espinoza");
        List<ServicioAsignado> compartidos = List.of(restaurante(titular, v, 2));

        byte[] pdf = renderer.renderGrupal(List.of(titular, acomp),
                Map.of(1L, compartidos, 2L, compartidos), true, 104);

        List<String> qrs = qrsDecodificados(pdf);
        assertEquals(1, qrs.size());
        String qr = qrs.get(0);
        assertTrue(qr.contains("2. FABIOLA/RODRIGUEZ ESPINOZA SGC-LIM-PU-000003"), qr);
        assertTrue(qr.contains("RESTAURANTE: LA ROSA NAUTICA RESTAURANT\nPax: 2\nIncluye: 2 desayunos"), qr);
        assertFalse(qr.contains("cubiertos"), "El ticket no imprime 'cubiertos', el QR tampoco");
        assertTrue(qr.chars().allMatch(c -> c == '\n' || (c >= 0x20 && c <= 0x7E)), "El QR debe ir en ASCII");

        // En el ticket impreso sí se conservan las tildes
        assertTrue(texto(pdf).contains("FABIOLA/RODRÍGUEZ ESPINOZA"));
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
    @DisplayName("Grupal de 6 pasajeros (máximo real) con servicios COMPARTIDOS → una página y QR legible con lo impreso")
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

        StringBuilder esperado = new StringBuilder("VOUCHER DE SERVICIO SGC-LIM-PU-000001\n"
                + "GRUPAL - 6 PASAJEROS\n"
                + "Vuelo: PU301/261020\n"
                + "PNR: ABC123\n"
                + "Lugar: LIM\n"
                + "PASAJEROS (6):\n");
        for (int i = 1; i <= 6; i++) {
            esperado.append(i).append(". PASAJERO").append(i).append("/APELLIDO").append(i)
                    .append(String.format(" SGC-LIM-PU-%06d", i)).append("\n");
        }
        esperado.append("HOTEL: NOVOTEL LIMA\n"
                + "Check-in: 20/10/2026\n"
                + "Check-out: 21/10/2026\n"
                + "Hab.: 3 DOBLE\n"
                + "Incluye: 3 desayunos, 3 almuerzos, 3 snacks, 3 cenas\n"
                + "TRANSPORTE: LIMA AIRPORT TRANSFER VIP\n"
                + "Pasajeros: 6\n"
                + "Tipo: AEROPUERTO - DOMICILIO Y DOMICILIO - AEROPUERTO\n"
                + "Plus Ultra - 20/10/2026");
        assertEquals(List.of(esperado.toString()), qrsDecodificados(pdf),
                "Con 6 pasajeros el QR debe generarse y repetir lo impreso en el ticket");
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
    // Grupos grandes (un PNR compartido no tiene límite; se han visto hasta 30)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Grupal de 10 pasajeros (límite) → el QR todavía lleva la lista completa de nombres")
    void renderGrupal_diezPasajeros_qrConListaCompleta() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        List<Atencion> grupo = grupo(v, TicketPdfRenderer.MAX_PASAJEROS_QR_DETALLE);
        List<ServicioAsignado> compartidos = List.of(hotel(grupo.get(0), v, "DOBLE", 5));
        Map<Long, List<ServicioAsignado>> mapa = new HashMap<>();
        grupo.forEach(a -> mapa.put(a.getId(), compartidos));

        List<String> qrs = qrsDecodificados(renderer.renderGrupal(grupo, mapa, true, 104));

        assertEquals(1, qrs.size());
        String qr = qrs.get(0);
        assertTrue(qr.contains("PASAJEROS (10):"), qr);
        assertTrue(qr.contains("1. PASAJERO1/APELLIDO1 SGC-LIM-PU-000001"), qr);
        assertTrue(qr.contains("10. PASAJERO10/APELLIDO10 SGC-LIM-PU-000010"), qr);
    }

    @Test
    @DisplayName("Grupal de 30 pasajeros COMPARTIDOS → una página, todos impresos y QR resumido con rango de correlativos")
    void renderGrupal_treintaPasajerosCompartidos_qrResumido() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        List<Atencion> grupo = grupo(v, 30);
        List<ServicioAsignado> compartidos = List.of(hotel(grupo.get(0), v, "DOBLE", 15), transporte(grupo.get(0), v, 30));
        Map<Long, List<ServicioAsignado>> mapa = new HashMap<>();
        grupo.forEach(a -> mapa.put(a.getId(), compartidos));

        byte[] pdf = renderer.renderGrupal(grupo, mapa, true, 104);

        assertPdfValido(pdf);
        try (PdfDocument doc = abrir(pdf)) {
            assertEquals(1, doc.getNumberOfPages(), "30 pasajeros deben salir en UNA sola tira");
            float altoMm = doc.getPage(1).getMediaBox().getHeight() / PT_POR_MM;
            assertTrue(altoMm < 600, "30 pasajeros compartidos ≈ 52 cm, no más de 60 cm (fue " + altoMm + " mm)");
        }

        // En el ticket impreso sí van los 30 pasajeros con su correlativo
        String texto = texto(pdf);
        assertTrue(texto.contains("30 PASAJEROS"));
        assertTrue(texto.contains("PASAJERO1/APELLIDO1"));
        assertTrue(texto.contains("PASAJERO30/APELLIDO30"));
        assertTrue(texto.contains("SGC-LIM-PU-000030"));

        String esperado = "VOUCHER DE SERVICIO SGC-LIM-PU-000001\n"
                + "GRUPAL - 30 PASAJEROS\n"
                + "Vuelo: PU301/261020\n"
                + "PNR: ABC123\n"
                + "Lugar: LIM\n"
                + "PASAJEROS: 30 (SGC-LIM-PU-000001 a SGC-LIM-PU-000030)\n"
                + "HOTEL: NOVOTEL LIMA\n"
                + "Check-in: 20/10/2026\n"
                + "Check-out: 21/10/2026\n"
                + "Hab.: 15 DOBLE\n"
                + "Incluye: 15 desayunos, 15 almuerzos, 15 snacks, 15 cenas\n"
                + "TRANSPORTE: LIMA AIRPORT TRANSFER VIP\n"
                + "Pasajeros: 30\n"
                + "Tipo: AEROPUERTO - DOMICILIO Y DOMICILIO - AEROPUERTO\n"
                + "Plus Ultra - 20/10/2026";
        assertEquals(List.of(esperado), qrsDecodificados(pdf),
                "Con 30 pasajeros el QR va resumido: sin la lista de nombres, con el rango de correlativos");
    }

    @Test
    @DisplayName("Grupal de 30 pasajeros INDEPENDIENTES → una página y QR resumido que remite al ticket impreso")
    void renderGrupal_treintaPasajerosIndependientes_qrResumido() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        List<Atencion> grupo = grupo(v, 30);
        Map<Long, List<ServicioAsignado>> mapa = new HashMap<>();
        grupo.forEach(a -> mapa.put(a.getId(), List.of(hotel(a, v, "SIMPLE", 1), transporte(a, v, 1))));

        byte[] pdf = renderer.renderGrupal(grupo, mapa, false, 80);

        assertPdfValido(pdf);
        try (PdfDocument doc = abrir(pdf)) {
            assertEquals(1, doc.getNumberOfPages());
        }
        assertTrue(texto(pdf).contains("PASAJERO 30/30"), "El ticket imprime la sección de cada pasajero");

        List<String> qrs = qrsDecodificados(pdf);
        assertEquals(1, qrs.size(), "Debe haber exactamente un QR legible");
        String qr = qrs.get(0);
        assertTrue(qr.contains("PASAJEROS: 30 (SGC-LIM-PU-000001 a SGC-LIM-PU-000030)"), qr);
        assertTrue(qr.contains("Servicios por pasajero: ver ticket impreso"), qr);
        assertFalse(qr.contains("PASAJERO 1/30"), "El QR no repite la sección de cada pasajero");
        assertFalse(qr.contains("HOTEL:"), "El QR no repite los servicios de cada pasajero");
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

    @Test
    @DisplayName("Logo con mucho margen vacío → se recorta antes de ponerlo en el ticket")
    void logo_conMargenVacio_seRecortaAlContenido() throws Exception {
        // Lienzo 600x600 transparente con el "logo" real de 300x100 al centro
        BufferedImage lienzo = new BufferedImage(600, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = lienzo.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(150, 250, 300, 100);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(lienzo, "png", out);

        Vuelo v = vuelo("LATAM");
        v.setLineaAereaId(7L);
        LineaAerea latam = LineaAerea.builder().id(7L).codigoIata("LA").nombre("LATAM")
                .logoKey("contingencias/logos/LA.png").build();
        when(lineaAereaRepository.findById(7L)).thenReturn(Optional.of(latam));
        when(s3StorageService.descargarPdf("contingencias/logos/LA.png")).thenReturn(out.toByteArray());
        Atencion a = atencion(v, 1L, "SGC-LIM-LA-000001", "Ana", "Ruiz");

        byte[] pdf = renderer.renderIndividual(a, List.of(), 80);

        // El logo embebido debe medir ~300x100 (+ margen), no 600x600
        try (PdfDocument doc = abrir(pdf)) {
            PdfDictionary xobjects = doc.getPage(1).getResources().getResource(PdfName.XObject);
            boolean logoRecortado = false;
            for (PdfName nombre : xobjects.keySet()) {
                PdfStream s = xobjects.getAsStream(nombre);
                if (s == null || !PdfName.Image.equals(s.getAsName(PdfName.Subtype))) continue;
                PdfImageXObject img = new PdfImageXObject(s);
                if (img.getWidth() < 400 && img.getHeight() < 200) logoRecortado = true;
            }
            assertTrue(logoRecortado, "El logo debe quedar recortado a su contenido");
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // Tamaño reducido de logo y título (ahorro de papel)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Logo 104 mm (individual) → cabe en la caja reducida de 45 × 14 mm")
    void logo_104mm_individual_cabeEnCajaReducida() throws Exception {
        Vuelo v = vueloConLogoLatam(pngColor(300, 100));
        Atencion a = atencion(v, 1L, "SGC-LIM-LA-000001", "Ana", "Ruiz");

        float[] logoMm = tamanoLogoMm(renderer.renderIndividual(a, List.of(), 104));

        // Logo 3:1 → lo limita el alto: 14 mm de alto × 42 mm de ancho
        assertEquals(14f, logoMm[1], 0.2f, "Alto del logo en rollo de 104 mm");
        assertEquals(42f, logoMm[0], 0.2f, "Ancho del logo en rollo de 104 mm");
    }

    @Test
    @DisplayName("Logo 104 mm (grupal) → misma caja reducida que el individual")
    void logo_104mm_grupal_cabeEnCajaReducida() throws Exception {
        Vuelo v = vueloConLogoLatam(pngColor(300, 100));
        Atencion titular = atencion(v, 1L, "SGC-LIM-LA-000002", "Juan", "Perez");
        Atencion acomp = atencion(v, 2L, "SGC-LIM-LA-000003", "Flor", "Vasquez");

        float[] logoMm = tamanoLogoMm(renderer.renderGrupal(List.of(titular, acomp),
                Map.of(1L, List.of(), 2L, List.of()), true, 104));

        assertEquals(14f, logoMm[1], 0.2f);
        assertEquals(42f, logoMm[0], 0.2f);
    }

    @Test
    @DisplayName("Logo 80 mm → cabe en la caja reducida de 38 × 12 mm")
    void logo_80mm_cabeEnCajaReducida() throws Exception {
        Vuelo v = vueloConLogoLatam(pngColor(300, 100));
        Atencion a = atencion(v, 1L, "SGC-LIM-LA-000001", "Ana", "Ruiz");

        float[] logoMm = tamanoLogoMm(renderer.renderIndividual(a, List.of(), 80));

        assertEquals(12f, logoMm[1], 0.2f, "Alto del logo en rollo de 80 mm");
        assertEquals(36f, logoMm[0], 0.2f, "Ancho del logo en rollo de 80 mm");
    }

    @Test
    @DisplayName("Logo muy ancho → lo limita el ancho máximo (45 mm en 104 mm)")
    void logo_104mm_muyAncho_limitadoPorAncho() throws Exception {
        Vuelo v = vueloConLogoLatam(pngColor(600, 100));
        Atencion a = atencion(v, 1L, "SGC-LIM-LA-000001", "Ana", "Ruiz");

        float[] logoMm = tamanoLogoMm(renderer.renderIndividual(a, List.of(), 104));

        // Logo 6:1 → lo limita el ancho: 45 mm × 7,5 mm
        assertEquals(45f, logoMm[0], 0.2f);
        assertEquals(7.5f, logoMm[1], 0.2f);
    }

    @Test
    @DisplayName("Título 104 mm → 12 pt; correlativo al tamaño del texto (9,5 pt)")
    void titulo_104mm_tamanoReducido() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, 1L, "SGC-LIM-PU-000001", "Ana", "Ruiz");

        Map<String, Float> tamanos = tamanosDeFuente(renderer.renderIndividual(a, List.of(), 104));

        assertEquals(12f, tamanos.get("VOUCHER DE SERVICIO"), 0.01f);
        assertEquals(9.5f, tamanos.get("SGC-LIM-PU-000001"), 0.01f);
    }

    @Test
    @DisplayName("Título 80 mm → 11 pt; correlativo al tamaño del texto (8,5 pt)")
    void titulo_80mm_tamanoReducido() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion a = atencion(v, 1L, "SGC-LIM-PU-000001", "Ana", "Ruiz");

        Map<String, Float> tamanos = tamanosDeFuente(renderer.renderIndividual(a, List.of(), 80));

        assertEquals(11f, tamanos.get("VOUCHER DE SERVICIO"), 0.01f);
        assertEquals(8.5f, tamanos.get("SGC-LIM-PU-000001"), 0.01f);
    }

    @Test
    @DisplayName("Título grupal 104 mm → 12 pt y subtítulo GRUPAL al tamaño del texto (9,5 pt)")
    void titulo_grupal_104mm_tamanoReducido() throws Exception {
        Vuelo v = vuelo("Plus Ultra");
        Atencion titular = atencion(v, 1L, "SGC-LIM-PU-000002", "Juan", "Perez");
        Atencion acomp = atencion(v, 2L, "SGC-LIM-PU-000003", "Flor", "Vasquez");

        Map<String, Float> tamanos = tamanosDeFuente(renderer.renderGrupal(List.of(titular, acomp),
                Map.of(1L, List.of(), 2L, List.of()), true, 104));

        assertEquals(12f, tamanos.get("VOUCHER DE SERVICIO"), 0.01f);
        assertEquals(9.5f, tamanos.get("GRUPAL · 2 PASAJEROS"), 0.01f);
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

    /** Vuelo LATAM cuyo logo se descarga (mock) de S3 con los bytes indicados. */
    private Vuelo vueloConLogoLatam(byte[] logo) throws Exception {
        Vuelo v = vuelo("LATAM");
        v.setLineaAereaId(7L);
        LineaAerea latam = LineaAerea.builder().id(7L).codigoIata("LA").nombre("LATAM")
                .logoKey("contingencias/logos/LA.png").build();
        when(lineaAereaRepository.findById(7L)).thenReturn(Optional.of(latam));
        when(s3StorageService.descargarPdf("contingencias/logos/LA.png")).thenReturn(logo);
        return v;
    }

    /**
     * Tamaño impreso del logo en mm {ancho, alto}, leído de la matriz con la
     * que se dibuja cada imagen. El QR es cuadrado, así que el logo es la
     * única imagen que no lo es.
     */
    private float[] tamanoLogoMm(byte[] pdf) throws Exception {
        List<float[]> imagenes = new ArrayList<>();
        procesar(pdf, (data, tipo) -> {
            if (tipo == EventType.RENDER_IMAGE) {
                Matrix ctm = ((ImageRenderInfo) data).getImageCtm();
                imagenes.add(new float[]{
                        Math.abs(ctm.get(Matrix.I11)) / PT_POR_MM,
                        Math.abs(ctm.get(Matrix.I22)) / PT_POR_MM});
            }
        });
        return imagenes.stream()
                .filter(t -> Math.abs(t[0] - t[1]) > 1f)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No se encontró el logo en el ticket"));
    }

    /** Tamaño de fuente de cada fragmento de texto (primera aparición). */
    private Map<String, Float> tamanosDeFuente(byte[] pdf) throws Exception {
        Map<String, Float> tamanos = new HashMap<>();
        procesar(pdf, (data, tipo) -> {
            if (tipo == EventType.RENDER_TEXT) {
                TextRenderInfo info = (TextRenderInfo) data;
                tamanos.putIfAbsent(info.getText().trim(), info.getFontSize());
            }
        });
        return tamanos;
    }

    private void procesar(byte[] pdf, java.util.function.BiConsumer<IEventData, EventType> consumidor) throws Exception {
        try (PdfDocument doc = abrir(pdf)) {
            new PdfCanvasProcessor(new IEventListener() {
                @Override
                public void eventOccurred(IEventData data, EventType tipo) {
                    consumidor.accept(data, tipo);
                }

                @Override
                public Set<EventType> getSupportedEvents() {
                    return Set.of(EventType.RENDER_IMAGE, EventType.RENDER_TEXT);
                }
            }).processPageContent(doc.getPage(1));
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