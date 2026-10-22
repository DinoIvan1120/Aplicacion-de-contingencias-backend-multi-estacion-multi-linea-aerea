package com.saasa.contingencias.service.impl;

import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.draw.DashedLine;
import com.itextpdf.kernel.pdf.canvas.draw.SolidLine;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.LineSeparator;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.layout.LayoutArea;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.renderer.DocumentRenderer;
import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;
import com.saasa.contingencias.domain.enumeration.OrigenFirmaEnum;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.model.LineaAerea;
import com.saasa.contingencias.domain.model.Proveedor;
import com.saasa.contingencias.domain.model.ServicioAsignado;
import com.saasa.contingencias.domain.model.Vuelo;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import com.saasa.contingencias.service.IS3StorageService;
import com.saasa.contingencias.service.impl.VoucherQr.Bloque;
import com.saasa.contingencias.service.impl.VoucherQr.Dato;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * NUEVO — Genera el voucher en formato TICKET para impresoras térmicas de
 * rollo (80 mm o 104 mm), tanto INDIVIDUAL como GRUPAL.
 *
 * No reemplaza ni modifica el voucher A4 (PdfGeneratorServiceImpl): es una
 * plantilla aparte que reutiliza los mismos datos y las mismas reglas:
 *  - logo dinámico de la aerolínea (LineaAerea.logoKey en S3, respaldo
 *    estático para Plus Ultra), aquí convertido a escala de grises;
 *  - idioma ES/EN según Atencion.idiomaVoucher (en grupal, el del titular);
 *  - QR de verificación armado por VoucherQr: el mismo contenido que se
 *    imprime en el ticket y el mismo QR que lleva el voucher A4;
 *  - la firma NO se imprime cuando origenFirma = AGENTE_LOTE.
 *
 * Página: ancho fijo y alto variable. Se dibuja en una página muy alta y
 * al final se recorta (MediaBox/CropBox) al alto real del contenido, para
 * que el ticket salga en UNA sola página sin espacio en blanco al final.
 * Todo se imprime en negro: las térmicas no imprimen color.
 */
@Component
public class TicketPdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(TicketPdfRenderer.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final float PT_POR_MM = 72f / 25.4f;
    /** Alto de trabajo (~4,9 m). Límite práctico de un PDF: 14400 pt. */
    private static final float ALTO_INICIAL_PT = 14000f;
    /** Zona no imprimible típica de las térmicas de rollo. */
    private static final float MARGEN_LATERAL_MM = 3.5f;
    private static final float MARGEN_VERTICAL_MM = 4f;

    private static final String LOGO_PLUS_ULTRA_PATH = "/images/plus-ultra-logo.png";

    private final LineaAereaRepository lineaAereaRepository;
    private final IS3StorageService s3StorageService;

    public TicketPdfRenderer(LineaAereaRepository lineaAereaRepository,
                             IS3StorageService s3StorageService) {
        this.lineaAereaRepository = lineaAereaRepository;
        this.s3StorageService = s3StorageService;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONFIGURACIÓN POR ANCHO DE ROLLO
    // ═══════════════════════════════════════════════════════════════════════

    private enum Estilo {
        // Logo y título reducidos para ahorrar papel (aprox. 12 mm menos por ticket).
        //          título  sección  texto  chico  qrMm  logoAnchoMm  logoAltoMm
        ANCHO_80(   11f,    10f,     8.5f,  7.5f,  40f,  38f,         12f),
        ANCHO_104(  12f,    11f,     9.5f,  8f,    46f,  45f,         14f);

        final float titulo, seccion, texto, chico, qrMm, logoAnchoMm, logoAltoMm;

        Estilo(float titulo, float seccion, float texto, float chico,
               float qrMm, float logoAnchoMm, float logoAltoMm) {
            this.titulo = titulo;
            this.seccion = seccion;
            this.texto = texto;
            this.chico = chico;
            this.qrMm = qrMm;
            this.logoAnchoMm = logoAnchoMm;
            this.logoAltoMm = logoAltoMm;
        }
    }

    /** Traductor ES/EN local a cada ticket (el bean es singleton). */
    private static final class Voz {
        private final IdiomaVoucherEnum idioma;

        private Voz(IdiomaVoucherEnum idioma) {
            this.idioma = idioma == null ? IdiomaVoucherEnum.ES : idioma;
        }

        private String t(String es, String en) {
            return VoucherQr.t(idioma, es, en);
        }
    }

    /** Estado de un ticket en construcción. */
    private static final class Ticket {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final PdfDocument pdf;
        final Document doc;
        final DocumentRenderer renderer;
        final float anchoPt;
        final float anchoUtilPt;
        final Estilo e;
        final Voz v;

        Ticket(int anchoMm, Voz v) {
            this.v = v;
            this.e = anchoMm >= 100 ? Estilo.ANCHO_104 : Estilo.ANCHO_80;
            this.anchoPt = mm(anchoMm);

            PageSize pageSize = new PageSize(anchoPt, ALTO_INICIAL_PT);
            this.pdf = new PdfDocument(new PdfWriter(baos));
            this.pdf.setDefaultPageSize(pageSize);
            this.doc = new Document(pdf, pageSize, false);
            this.renderer = new DocumentRenderer(doc, false);
            this.doc.setRenderer(renderer);

            float lateral = mm(MARGEN_LATERAL_MM);
            float vertical = mm(MARGEN_VERTICAL_MM);
            this.doc.setMargins(vertical, lateral, vertical, lateral);
            this.anchoUtilPt = anchoPt - 2 * lateral;
        }
    }

    private static float mm(float milimetros) {
        return milimetros * PT_POR_MM;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TICKET INDIVIDUAL
    // ═══════════════════════════════════════════════════════════════════════

    public byte[] renderIndividual(Atencion atencion, List<ServicioAsignado> servicios, int anchoMm) {
        log.info("🧾 Generando ticket {} mm (individual) para: {}", anchoMm, atencion.getNumeroCorrelativo());
        Ticket t = new Ticket(anchoMm, new Voz(atencion.getIdiomaVoucher()));
        Vuelo vuelo = atencion.getVuelo();

        logo(t, vuelo);
        titulo(t, null, atencion.getNumeroCorrelativo());

        separador(t);
        dato(t, t.v.t("Pasajero:", "Passenger:"), nombreConBarra(atencion));
        dato(t, t.v.t("Vuelo:", "Flight:"), vueloInfo(atencion));
        dato(t, "PNR:", atencion.getPnr());
        dato(t, t.v.t("Lugar:", "Site:"), lugar(atencion));

        bloqueServicios(t, servicios, true);

        qr(t, VoucherQr.contenidoIndividual(atencion, servicios, t.v.idioma));
        firma(t, atencion);
        pie(t, atencion);

        return cerrar(t);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TICKET GRUPAL
    // ═══════════════════════════════════════════════════════════════════════

    public byte[] renderGrupal(List<Atencion> atenciones,
                               Map<Long, List<ServicioAsignado>> serviciosPorAtencion,
                               boolean serviciosCompartidos,
                               int anchoMm) {
        Atencion titular = atenciones.get(0);
        int total = atenciones.size();
        log.info("🧾 Generando ticket {} mm (GRUPAL, {} pasajeros, compartidos={}) a partir de: {}",
                anchoMm, total, serviciosCompartidos, titular.getNumeroCorrelativo());

        // Igual que el A4 grupal: el idioma es el del TITULAR.
        Ticket t = new Ticket(anchoMm, new Voz(titular.getIdiomaVoucher()));
        Vuelo vuelo = titular.getVuelo();

        logo(t, vuelo);
        titulo(t,
                t.v.t("GRUPAL · ", "GROUP · ") + total + t.v.t(" PASAJEROS", " PASSENGERS"),
                titular.getNumeroCorrelativo());

        separador(t);
        dato(t, t.v.t("Vuelo:", "Flight:"), vueloInfo(titular));
        dato(t, "PNR:", titular.getPnr());
        dato(t, t.v.t("Lugar:", "Site:"), lugar(titular));

        // ── Lista de pasajeros del grupo con su correlativo ──
        separador(t);
        seccion(t, t.v.t("PASAJEROS (", "PASSENGERS (") + total + "):");
        int i = 1;
        for (Atencion a : atenciones) {
            t.doc.add(parrafo(i + ". " + nombreConBarra(a), t.e.texto).setBold().setMarginTop(i == 1 ? 1 : 3));
            t.doc.add(parrafo(a.getNumeroCorrelativo(), t.e.chico).setMarginLeft(mm(4)));
            i++;
        }

        if (serviciosCompartidos) {
            // ── Un solo bloque de servicios para todo el grupo (como el A4) ──
            List<ServicioAsignado> servicios = serviciosPorAtencion.getOrDefault(titular.getId(), List.of());
            bloqueServicios(t, servicios, true);
        } else {
            // ── Una sección por pasajero con sus propios servicios ──
            i = 1;
            for (Atencion a : atenciones) {
                List<ServicioAsignado> servicios = serviciosPorAtencion.getOrDefault(a.getId(), List.of());

                t.doc.add(new LineSeparator(new SolidLine(1f)).setMarginTop(6).setMarginBottom(4));
                seccion(t, t.v.t("PASAJERO ", "PASSENGER ") + i + "/" + total);
                t.doc.add(parrafo(nombreConBarra(a), t.e.texto).setBold());
                t.doc.add(parrafo(a.getNumeroCorrelativo(), t.e.chico));

                bloqueServicios(t, servicios, false);
                i++;
            }
            t.doc.add(new LineSeparator(new SolidLine(1f)).setMarginTop(6).setMarginBottom(2));
        }

        qr(t, VoucherQr.contenidoGrupal(atenciones, serviciosPorAtencion, serviciosCompartidos, t.v.idioma));
        // Firma del titular (un solo comprobante para todo el grupo), misma regla que el A4.
        firma(t, titular);
        pie(t, titular);

        return cerrar(t);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BLOQUES DEL TICKET
    // ═══════════════════════════════════════════════════════════════════════

    private void logo(Ticket t, Vuelo vuelo) {
        byte[] bytes = cargarLogo(vuelo);
        if (bytes == null) return; // sin logo: encabezado sin texto, igual que el A4
        try {
            Image img = new Image(ImageDataFactory.create(aEscalaDeGrises(bytes)));
            img.scaleToFit(mm(t.e.logoAnchoMm), mm(t.e.logoAltoMm));
            img.setHorizontalAlignment(HorizontalAlignment.CENTER);
            t.doc.add(img);
        } catch (Exception ex) {
            log.warn("⚠️ No se pudo agregar el logo al ticket: {}", ex.getMessage());
        }
    }

    private void titulo(Ticket t, String subtitulo, String correlativo) {
        t.doc.add(parrafo(t.v.t("VOUCHER DE SERVICIO", "SERVICE VOUCHER"), t.e.titulo)
                .setBold()
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginTop(2));
        if (subtitulo != null) {
            t.doc.add(parrafo(subtitulo, t.e.texto)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER));
        }
        t.doc.add(parrafo(correlativo, t.e.texto)
                .setTextAlignment(TextAlignment.CENTER));
    }

    /**
     * Imprime hotel, transporte y restaurante como bloques de texto (en el
     * ticket no hay tablas): proveedor, fechas, cantidades, lo que incluye,
     * dirección y teléfono.
     *
     * @param separarConLineas true: línea punteada antes de cada bloque;
     *                         false: solo un espacio (usado dentro de la
     *                         sección de cada pasajero del ticket grupal).
     */
    private void bloqueServicios(Ticket t, List<ServicioAsignado> servicios, boolean separarConLineas) {
        List<Bloque> bloques = VoucherQr.bloques(servicios, t.v.idioma);
        if (bloques.isEmpty()) {
            if (separarConLineas) separador(t);
            t.doc.add(parrafo(VoucherQr.sinServicios(t.v.idioma), t.e.texto)
                    .setItalic().setMarginTop(separarConLineas ? 0 : 4));
            return;
        }
        for (Bloque b : bloques) {
            inicioBloque(t, separarConLineas);
            seccion(t, b.titulo());
            for (Dato d : b.datos()) {
                dato(t, d.etiqueta(), d.valor());
            }
            contacto(t, b.proveedor());
        }
    }

    private void inicioBloque(Ticket t, boolean separarConLineas) {
        if (separarConLineas) {
            separador(t);
        } else {
            t.doc.add(parrafo(" ", 3f));
        }
    }

    private void contacto(Ticket t, Proveedor proveedor) {
        if (proveedor == null) return;
        if (proveedor.getDireccion() != null && !proveedor.getDireccion().isBlank()) {
            t.doc.add(parrafo(proveedor.getDireccion(), t.e.chico).setMarginTop(1));
        }
        if (proveedor.getTelefono() != null && !proveedor.getTelefono().isBlank()) {
            t.doc.add(parrafo("Tel: " + proveedor.getTelefono(), t.e.chico));
        }
    }

    private void qr(Ticket t, String contenido) {
        separador(t);
        t.doc.add(parrafo(t.v.t("CÓDIGO DE VERIFICACIÓN", "VERIFICATION CODE"), t.e.texto + 0.5f)
                .setBold()
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(3));
        try {
            Image img = new Image(ImageDataFactory.create(VoucherQr.png(contenido)));
            float lado = mm(t.e.qrMm);
            img.setWidth(lado);
            img.setHeight(lado);
            img.setHorizontalAlignment(HorizontalAlignment.CENTER);
            t.doc.add(img);
            t.doc.add(parrafo(t.v.t("Escanee para verificar el voucher", "Scan to verify the voucher"), t.e.chico)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(3));
        } catch (Exception ex) {
            log.warn("No se pudo generar el QR del ticket: {}", ex.getMessage());
        }
    }

    /**
     * Misma regla de negocio que el A4: se imprime solo si firmó el propio
     * pasajero. Con origenFirma = AGENTE_LOTE (carga masiva) NO se imprime.
     */
    private void firma(Ticket t, Atencion atencion) {
        String firma = atencion.getFirmaPasajero();
        if (firma == null || firma.isBlank()) return;
        if (atencion.getOrigenFirma() == OrigenFirmaEnum.AGENTE_LOTE) return;

        separador(t);
        t.doc.add(parrafo(t.v.t("Firma de conformidad:", "Signature of consent:"), t.e.texto)
                .setBold()
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(2));

        if (firma.startsWith("data:image")) {
            try {
                byte[] firmaBytes = Base64.getDecoder().decode(firma.substring(firma.indexOf(',') + 1));
                Image img = new Image(ImageDataFactory.create(firmaBytes));
                img.scaleToFit(t.anchoUtilPt * 0.7f, mm(22));
                img.setHorizontalAlignment(HorizontalAlignment.CENTER);
                t.doc.add(img);
            } catch (Exception ex) {
                log.warn("⚠️ No se pudo decodificar la firma para el ticket: {}", ex.getMessage());
                t.doc.add(parrafo(t.v.t("(Firma no disponible)", "(Signature unavailable)"), t.e.chico)
                        .setItalic()
                        .setTextAlignment(TextAlignment.CENTER));
            }
            String nombre = (texto(atencion.getNombre()) + " " + texto(atencion.getApellido())).trim();
            if (!nombre.isBlank()) {
                t.doc.add(parrafo(nombre.toUpperCase(), t.e.texto)
                        .setBold()
                        .setTextAlignment(TextAlignment.CENTER)
                        .setMarginTop(3));
            }
        } else {
            t.doc.add(parrafo(firma, t.e.texto + 1f)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER));
        }

        if (atencion.getFirmaFecha() != null) {
            t.doc.add(parrafo(t.v.t("Firmado el ", "Signed on ") + atencion.getFirmaFecha().format(FMT), t.e.chico)
                    .setTextAlignment(TextAlignment.CENTER));
        }
    }

    private void pie(Ticket t, Atencion atencion) {
        t.doc.add(parrafo(textoPie(atencion), t.e.chico)
                .setItalic()
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginTop(8));
    }

    /**
     * Recorta la página al alto real del contenido y cierra el documento.
     * Si el contenido excediera el alto de trabajo (grupo enorme), se deja
     * en varias páginas sin recortar para no perder información.
     */
    private byte[] cerrar(Ticket t) {
        LayoutArea area = t.renderer.getCurrentArea();
        int paginas = t.pdf.getNumberOfPages();

        if (area != null && paginas == 1) {
            float finContenido = area.getBBox().getTop();
            float inferior = Math.max(0f, finContenido - mm(MARGEN_VERTICAL_MM));
            Rectangle caja = new Rectangle(0, inferior, t.anchoPt, ALTO_INICIAL_PT - inferior);
            PdfPage pagina = t.pdf.getPage(1);
            pagina.setMediaBox(caja);
            pagina.setCropBox(caja);
        } else if (paginas > 1) {
            log.warn("⚠️ El ticket ocupó {} páginas; se deja sin recortar", paginas);
        }

        t.doc.close();
        byte[] bytes = t.baos.toByteArray();
        log.info("✅ Ticket generado: {} bytes", bytes.length);
        return bytes;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LOGO
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Mismo origen del logo que el A4: LineaAerea.logoKey en S3 y, si es
     * Plus Ultra sin logo en S3, el logo estático del classpath. Sin caché:
     * el ticket se imprime bajo demanda y así nunca sirve un logo viejo.
     */
    private byte[] cargarLogo(Vuelo vuelo) {
        if (vuelo == null) return null;
        Long lineaAereaId = vuelo.getLineaAereaId();
        if (lineaAereaId != null) {
            LineaAerea lineaAerea = lineaAereaRepository.findById(lineaAereaId).orElse(null);
            if (lineaAerea != null && lineaAerea.getLogoKey() != null && !lineaAerea.getLogoKey().isBlank()) {
                try {
                    return s3StorageService.descargarPdf(lineaAerea.getLogoKey());
                } catch (Exception ex) {
                    log.warn("⚠️ No se pudo descargar el logo (lineaAereaId={}) para el ticket: {}",
                            lineaAereaId, ex.getMessage());
                }
            }
        }
        String aerolinea = vuelo.getAerolinea();
        if (aerolinea != null && aerolinea.replaceAll("\\s+", "").equalsIgnoreCase("PlusUltra")) {
            try (InputStream is = getClass().getResourceAsStream(LOGO_PLUS_ULTRA_PATH)) {
                return is != null ? is.readAllBytes() : null;
            } catch (Exception ex) {
                log.warn("⚠️ No se pudo cargar el logo de respaldo de Plus Ultra: {}", ex.getMessage());
            }
        }
        return null;
    }

    /**
     * Convierte el logo a escala de grises sobre fondo blanco (las zonas
     * transparentes quedan blancas) y RECORTA los bordes vacíos, para que el
     * margen que traiga la imagen subida no achique el logo en el ticket.
     * Si el formato no es legible por ImageIO, devuelve los bytes originales.
     */
    private byte[] aEscalaDeGrises(byte[] bytes) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(bytes));
            if (original == null) return bytes;

            int w = original.getWidth();
            int h = original.getHeight();
            BufferedImage gris = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = gris.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(original, 0, 0, null);
            g.dispose();

            BufferedImage recortada = recortarBordesBlancos(gris);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(recortada, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            log.warn("⚠️ No se pudo preparar el logo para el ticket: {}", ex.getMessage());
            return bytes;
        }
    }

    /**
     * Devuelve la imagen recortada al área con contenido (píxeles que no son
     * casi blancos), dejando un margen de 2 % para que el logo no quede pegado
     * al borde. Si la imagen está vacía o ya no tiene margen, la devuelve igual.
     */
    private BufferedImage recortarBordesBlancos(BufferedImage gris) {
        final int UMBRAL = 245; // 0 = negro, 255 = blanco
        int w = gris.getWidth();
        int h = gris.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;

        var raster = gris.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (raster.getSample(x, y, 0) < UMBRAL) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return gris; // imagen completamente blanca

        int margen = Math.max(2, Math.round(Math.max(w, h) * 0.02f));
        minX = Math.max(0, minX - margen);
        minY = Math.max(0, minY - margen);
        maxX = Math.min(w - 1, maxX + margen);
        maxY = Math.min(h - 1, maxY + margen);

        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) return gris;
        return gris.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AUXILIARES
    // ═══════════════════════════════════════════════════════════════════════

    private Paragraph parrafo(String texto, float tamano) {
        return new Paragraph(texto == null ? "" : texto)
                .setFontSize(tamano)
                .setMargin(0)
                .setMultipliedLeading(1.15f);
    }

    private void seccion(Ticket t, String texto) {
        t.doc.add(parrafo(texto, t.e.seccion).setBold().setMarginBottom(1));
    }

    private void dato(Ticket t, String etiqueta, String valor) {
        t.doc.add(new Paragraph()
                .add(new Text(etiqueta + " ").setBold())
                .add(new Text(valor == null || valor.isBlank() ? "-" : valor))
                .setFontSize(t.e.texto)
                .setMargin(0)
                .setMultipliedLeading(1.15f));
    }

    private void separador(Ticket t) {
        t.doc.add(new LineSeparator(new DashedLine(0.6f)).setMarginTop(4).setMarginBottom(4));
    }

    private String nombreConBarra(Atencion a) {
        return VoucherQr.nombreConBarra(a);
    }

    private String vueloInfo(Atencion a) {
        return VoucherQr.vueloInfo(a);
    }

    private String lugar(Atencion a) {
        return VoucherQr.lugar(a);
    }

    private String textoPie(Atencion a) {
        return VoucherQr.textoPie(a);
    }

    private String texto(String s) {
        return VoucherQr.texto(s);
    }
}