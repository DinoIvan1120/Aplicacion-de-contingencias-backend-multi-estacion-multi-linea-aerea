package com.saasa.contingencias.service.impl;

import com.google.zxing.*;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.canvas.draw.SolidLine;
import com.itextpdf.layout.element.LineSeparator;
import com.itextpdf.layout.*;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.*;
import com.saasa.contingencias.domain.enumeration.OrigenFirmaEnum;
import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import com.saasa.contingencias.service.IPdfGeneratorService;
import com.saasa.contingencias.service.IS3StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class PdfGeneratorServiceImpl implements IPdfGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(PdfGeneratorServiceImpl.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter FMT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Colores corporativos Plus Ultra
    private static final DeviceRgb COLOR_ROJO_PLUS_ULTRA = new DeviceRgb(228, 24, 24);
    private static final DeviceRgb COLOR_GRIS_OSCURO = new DeviceRgb(60, 60, 60);
    private static final DeviceRgb COLOR_GRIS_TABLA = new DeviceRgb(240, 240, 240);

    // NUEVO — Logo oficial de la aerolínea en el header del voucher
    private static final String LOGO_PLUS_ULTRA_PATH = "/images/plus-ultra-logo.png";
    private byte[] logoPlusUltraCache;

    private final LineaAereaRepository lineaAereaRepository;
    private final IS3StorageService s3StorageService;

    // Cache en memoria del logo (bytes) de cada aerolínea por lineaAereaId,
    // para no ir a S3 en cada voucher generado. Como el logo de una
    // aerolínea rara vez cambia, no hace falta invalidarlo por tiempo.
    private final Map<Long, byte[]> logoCachePorLineaAerea = new ConcurrentHashMap<>();

    public PdfGeneratorServiceImpl(LineaAereaRepository lineaAereaRepository,
                                   IS3StorageService s3StorageService) {
        this.lineaAereaRepository = lineaAereaRepository;
        this.s3StorageService = s3StorageService;
    }
    /**
     * Carga el logo desde el classpath — único respaldo estático que queda,
     * y solo se usa para Plus Ultra si aún no tiene logo dinámico en S3.
     */
    private byte[] cargarLogoPlusUltraDeRespaldo() {
        if (logoPlusUltraCache != null) return logoPlusUltraCache;
        try (var is = getClass().getResourceAsStream(LOGO_PLUS_ULTRA_PATH)) {
            if (is == null) {
                return null;
            }
            logoPlusUltraCache = is.readAllBytes();
            return logoPlusUltraCache;
        } catch (Exception e) {
            log.warn("⚠️ No se pudo cargar el logo de respaldo de Plus Ultra: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void invalidarCacheLogo(Long lineaAereaId) {
        logoCachePorLineaAerea.remove(lineaAereaId);
        log.info("🗑️ Caché de logo invalidado para línea aérea id={}", lineaAereaId);
    }


    /**
     * Carga el logo dinámico de la aerolínea (multi-línea-aérea, multi-estación).
     * Busca la LineaAerea por su id (el mismo id con el que se creó/asignó el
     * vuelo), toma su logoKey y descarga los bytes desde S3. Se cachea en
     * memoria por lineaAereaId para no golpear S3 en cada voucher.
     * Devuelve null si la línea no existe, no tiene lineaAereaId o no tiene
     * logo subido — en ese caso el header cae al texto de siempre.
     */
    private byte[] cargarLogoAerolinea(Long lineaAereaId) {
        if (lineaAereaId == null) return null;

        byte[] cacheado = logoCachePorLineaAerea.get(lineaAereaId);
        if (cacheado != null) return cacheado;

        LineaAerea lineaAerea = lineaAereaRepository.findById(lineaAereaId).orElse(null);
        if (lineaAerea == null || lineaAerea.getLogoKey() == null || lineaAerea.getLogoKey().isBlank()) {
            return null;
        }

        try {
            byte[] bytes = s3StorageService.descargarPdf(lineaAerea.getLogoKey());
            logoCachePorLineaAerea.put(lineaAereaId, bytes);
            return bytes;
        } catch (Exception e) {
            log.warn("⚠️ No se pudo descargar el logo de la línea aérea (id={}): {}", lineaAereaId, e.getMessage());
            return null;
        }
    }

    /**
     * Agrega el header del voucher con el logo oficial de la aerolínea
     * correspondiente a la estación/línea aérea del vuelo. El logo se
     * resuelve dinámicamente vía lineaAereaId (LineaAerea.logoKey en S3);
     * si esa aerolínea aún no tiene logo cargado, cae al header de texto
     * de siempre (nombre en rojo + "LÍNEAS AÉREAS"). Se mantiene la misma
     * posición y el mismo tamaño (140x180) que tenía el logo estático de
     * Plus Ultra, solo cambia de dónde sale la imagen.
     */
    private void agregarHeaderAerolinea(Document doc, String nombreAerolinea, Long lineaAereaId) {
        byte[] logoBytes = cargarLogoAerolinea(lineaAereaId);

        // Respaldo: si es Plus Ultra y todavía no migró su logo a S3, usa
        // el logo estático embebido para no romper su voucher actual.
        if (logoBytes == null && nombreAerolinea != null
                && nombreAerolinea.replaceAll("\\s+", "").equalsIgnoreCase("PlusUltra")) {
            logoBytes = cargarLogoPlusUltraDeRespaldo();
        }

        if (logoBytes != null) {
            Image logo = new Image(ImageDataFactory.create(logoBytes));
            logo.scaleToFit(140, 180);
            doc.add(logo);
        } else {
            doc.add(new Paragraph()
                    .add(new Text(nombreAerolinea.toUpperCase() + "\n")
                            .setFontSize(24).setBold().setFontColor(COLOR_ROJO_PLUS_ULTRA))
                    .add(new Text("LÍNEAS AÉREAS")
                            .setFontSize(10).setFontColor(COLOR_GRIS_OSCURO))
                    .setTextAlignment(TextAlignment.LEFT));
        }
    }

    /**
     * NUEVO — Traductor de etiquetas del voucher (ES/EN). Se crea una
     * instancia LOCAL nueva en cada llamada a generarVoucher/
     * generarVoucherGrupal y se pasa como parámetro a los métodos
     * auxiliares — nunca se guarda el idioma en un campo de instancia del
     * service, porque es un bean Spring singleton compartido entre
     * requests concurrentes.
     */
    private static final class Voz {
        private final IdiomaVoucherEnum idioma;

        private Voz(IdiomaVoucherEnum idioma) {
            this.idioma = idioma == null ? IdiomaVoucherEnum.ES : idioma;
        }

        /** Devuelve el texto en español o en inglés según el idioma del voucher. */
        private String t(String es, String en) {
            return idioma == IdiomaVoucherEnum.EN ? en : es;
        }
    }

    @Override
    public byte[] generarVoucher(Atencion atencion, List<ServicioAsignado> servicios) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            log.info("📄 Generando PDF voucher para: {}", atencion.getNumeroCorrelativo());

            Voz v = new Voz(atencion.getIdiomaVoucher());

            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdfDoc = new PdfDocument(writer);
            Document doc = new Document(pdfDoc);
            doc.setMargins(40, 40, 40, 40);

            // ═══════════════════════════════════════════════════════════════════════
            // HEADER: Logo y título
            // ═══════════════════════════════════════════════════════════════════════

            Vuelo vuelo = atencion.getVuelo();
            String nombreAerolinea = vuelo.getAerolinea() != null ?
                    vuelo.getAerolinea() : "PLUS ULTRA";

            agregarHeaderAerolinea(doc, nombreAerolinea, vuelo.getLineaAereaId());

            doc.add(new Paragraph("\n").setFontSize(6));

            // Título del voucher
            Paragraph titulo = new Paragraph(v.t("*** VOUCHER DE SERVICIO ***", "*** SERVICE VOUCHER ***"))
                    .setFontSize(16)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER);

            doc.add(titulo);
            doc.add(new Paragraph("\n").setFontSize(4));
            doc.add(new LineSeparator(new SolidLine(1f)));
            doc.add(new Paragraph("\n").setFontSize(6));

            // ═══════════════════════════════════════════════════════════════════════
            // INFORMACIÓN DEL PASAJERO Y VUELO
            // ═══════════════════════════════════════════════════════════════════════

            String nombreCompleto = atencion.getNombre() + "/" + atencion.getApellido();

            Table infoTable = new Table(UnitValue.createPercentArray(new float[]{1, 3}))
                    .useAllAvailableWidth()
                    .setMarginBottom(10);

            // Número de voucher
            infoTable.addCell(createLabelCell(v.t("Número:", "Number:")));
            infoTable.addCell(createValueCell(atencion.getNumeroCorrelativo()));

            // Nombre
            infoTable.addCell(createLabelCell(v.t("Nombre:", "Name:")));
            infoTable.addCell(createValueCell(nombreCompleto.toUpperCase()));

            // Vuelo
            infoTable.addCell(createLabelCell(v.t("Vuelo:", "Flight:")));
            String vueloInfo = vuelo.getCodigoVuelo();
            if (atencion.getFechaEmision() != null) {
                vueloInfo += "/" + atencion.getFechaEmision().format(DateTimeFormatter.ofPattern("yyMMdd"));
            }
            infoTable.addCell(createValueCell(vueloInfo));

            // PNR
            infoTable.addCell(createLabelCell("PNR:"));
            infoTable.addCell(createValueCell(atencion.getPnr()));

            doc.add(infoTable);
            doc.add(new Paragraph("\n").setFontSize(6));

            // ═══════════════════════════════════════════════════════════════════════
            // SERVICIOS ASIGNADOS (RESUMEN)
            // ═══════════════════════════════════════════════════════════════════════

            agregarResumenServicios(doc, servicios, v);

            // Información de emisión
            Table emisionTable = new Table(UnitValue.createPercentArray(new float[]{1, 3}))
                    .useAllAvailableWidth()
                    .setMarginTop(10);

            emisionTable.addCell(createLabelCell(v.t("Emitido:", "Issued:")));
            emisionTable.addCell(createValueCell(atencion.getNumeroCorrelativo()));

            emisionTable.addCell(createLabelCell(v.t("Fecha:", "Date:")));
            String fechaEmision = atencion.getFechaEmision() != null ?
                    atencion.getFechaEmision().format(DateTimeFormatter.ofPattern("yyMMdd")) + " OT" :
                    (atencion.getCreatedAt() != null ?
                            atencion.getCreatedAt().format(DateTimeFormatter.ofPattern("yyMMdd")) + " OT" : "N/A");
            emisionTable.addCell(createValueCell(fechaEmision));

            emisionTable.addCell(createLabelCell(v.t("Lugar:", "Site:")));
            emisionTable.addCell(createValueCell(
                    atencion.getLugarEmision() != null ? atencion.getLugarEmision() : "LIM"
            ));

            doc.add(emisionTable);
            doc.add(new Paragraph("\n").setFontSize(10));

            // ═══════════════════════════════════════════════════════════════════════
            // TABLA DETALLADA DE SERVICIOS CON PROVEEDORES
            // ═══════════════════════════════════════════════════════════════════════

            agregarTablaDetalleServicios(doc, servicios, v);

            // ═══════════════════════════════════════════════════════════════════════
            // CÓDIGO QR DE VERIFICACIÓN
            // ═══════════════════════════════════════════════════════════════════════

            agregarCodigoQR(doc, atencion, servicios, v);

            // ═══════════════════════════════════════════════════════════════════════
            // FIRMA DE CONFORMIDAD                                    ← NUEVO
            // ═══════════════════════════════════════════════════════════════════════

            agregarFirmaConformidad(doc, atencion, v);                 // ← NUEVO

            // ═══════════════════════════════════════════════════════════════════════
            // FOOTER
            // ═══════════════════════════════════════════════════════════════════════

            doc.add(new Paragraph("\n").setFontSize(15));

            String fecha = atencion.getFechaEmision() != null ?
                    atencion.getFechaEmision().format(FMT_DATE) :
                    (atencion.getCreatedAt() != null ?
                            atencion.getCreatedAt().toLocalDate().format(FMT_DATE) :
                            java.time.LocalDate.now().format(FMT_DATE));

            doc.add(new Paragraph("Línea Aérea - " + nombreAerolinea + " - " + fecha)
                    .setFontSize(8)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontColor(COLOR_GRIS_OSCURO)
                    .setItalic());

            doc.close();

            byte[] pdfBytes = baos.toByteArray();
            log.info("✅ PDF generado exitosamente: {} bytes", pdfBytes.length);

            return pdfBytes;

        } catch (Exception e) {
            log.error("❌ Error generando PDF voucher: {}", e.getMessage(), e);
            throw new RuntimeException("Error generando voucher PDF", e);
        }
    }

    @Override
    public byte[] generarVoucherGrupal(List<Atencion> atenciones,
                                       Map<Long, List<ServicioAsignado>> serviciosPorAtencion,
                                       boolean serviciosCompartidos) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Atencion titular = atenciones.get(0);
            log.info("📄 Generando PDF voucher GRUPAL ({} pasajeros) a partir de: {}",
                    atenciones.size(), titular.getNumeroCorrelativo());

            // NUEVO: el idioma del voucher grupal es el del TITULAR — es un
            // solo PDF compartido por todo el grupo.
            Voz v = new Voz(titular.getIdiomaVoucher());

            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdfDoc = new PdfDocument(writer);
            Document doc = new Document(pdfDoc);
            doc.setMargins(40, 40, 40, 40);

            // ═══════════════════════════════════════════════════════════════════════
            // HEADER: Logo y título (igual que el voucher individual)
            // ═══════════════════════════════════════════════════════════════════════

            Vuelo vuelo = titular.getVuelo();
            String nombreAerolinea = vuelo.getAerolinea() != null ? vuelo.getAerolinea() : "PLUS ULTRA";

            agregarHeaderAerolinea(doc, nombreAerolinea, vuelo.getLineaAereaId());
            doc.add(new Paragraph("\n").setFontSize(6));

            doc.add(new Paragraph(v.t("*** VOUCHER DE SERVICIO ***", "*** SERVICE VOUCHER ***"))
                    .setFontSize(16).setBold().setTextAlignment(TextAlignment.CENTER));
            doc.add(new Paragraph("\n").setFontSize(4));
            doc.add(new LineSeparator(new SolidLine(1f)));
            doc.add(new Paragraph("\n").setFontSize(6));

            // ═══════════════════════════════════════════════════════════════════════
            // INFORMACIÓN DEL GRUPO: PASAJEROS (lista), vuelo y PNR (compartidos)
            // ═══════════════════════════════════════════════════════════════════════

            Table infoTable = new Table(UnitValue.createPercentArray(new float[]{1, 3}))
                    .useAllAvailableWidth()
                    .setMarginBottom(10);

            String correlativosGrupo = atenciones.stream()
                    .map(Atencion::getNumeroCorrelativo)
                    .collect(Collectors.joining(", "));
            infoTable.addCell(createLabelCell(v.t("Número:", "Number:")));
            infoTable.addCell(createValueCell(correlativosGrupo));

            String listaPasajeros = atenciones.stream()
                    .map(a -> (a.getNombre() + "/" + a.getApellido()).toUpperCase())
                    .collect(Collectors.joining("\n"));
            infoTable.addCell(createLabelCell(v.t("Pasajeros:", "Passengers:")));
            infoTable.addCell(createValueCell(listaPasajeros));

            infoTable.addCell(createLabelCell(v.t("Vuelo:", "Flight:")));
            String vueloInfo = vuelo.getCodigoVuelo();
            if (titular.getFechaEmision() != null) {
                vueloInfo += "/" + titular.getFechaEmision().format(DateTimeFormatter.ofPattern("yyMMdd"));
            }
            infoTable.addCell(createValueCell(vueloInfo));

            infoTable.addCell(createLabelCell("PNR:"));
            infoTable.addCell(createValueCell(titular.getPnr()));

            doc.add(infoTable);
            doc.add(new Paragraph("\n").setFontSize(6));

            // Información de emisión (compartida por todo el grupo)
            Table emisionTable = new Table(UnitValue.createPercentArray(new float[]{1, 3}))
                    .useAllAvailableWidth()
                    .setMarginTop(4)
                    .setMarginBottom(10);
            emisionTable.addCell(createLabelCell(v.t("Emitido:", "Issued:")));
            emisionTable.addCell(createValueCell(titular.getNumeroCorrelativo()));
            emisionTable.addCell(createLabelCell(v.t("Fecha:", "Date:")));
            String fechaEmisionGrupo = titular.getFechaEmision() != null ?
                    titular.getFechaEmision().format(DateTimeFormatter.ofPattern("yyMMdd")) + " OT" :
                    (titular.getCreatedAt() != null ?
                            titular.getCreatedAt().format(DateTimeFormatter.ofPattern("yyMMdd")) + " OT" : "N/A");
            emisionTable.addCell(createValueCell(fechaEmisionGrupo));
            emisionTable.addCell(createLabelCell(v.t("Lugar:", "Site:")));
            emisionTable.addCell(createValueCell(titular.getLugarEmision() != null ? titular.getLugarEmision() : "LIM"));
            doc.add(emisionTable);

            // ═══════════════════════════════════════════════════════════════════════
            // SERVICIOS
            // ═══════════════════════════════════════════════════════════════════════

            if (serviciosCompartidos) {
                // ── Un solo bloque de servicios, igual para todo el grupo ──
                List<ServicioAsignado> servicios = serviciosPorAtencion.getOrDefault(titular.getId(), List.of());
                agregarResumenServicios(doc, servicios, v);
                agregarTablaDetalleServicios(doc, servicios, v);
            } else {
                // ── Una sección por pasajero, cada una con sus propios servicios ──
                for (Atencion a : atenciones) {
                    List<ServicioAsignado> servicios = serviciosPorAtencion.getOrDefault(a.getId(), List.of());

                    doc.add(new Paragraph(v.t("PASAJERO: ", "PASSENGER: ") + (a.getNombre() + " " + a.getApellido()).toUpperCase()
                            + "  (" + a.getNumeroCorrelativo() + ")")
                            .setBold()
                            .setFontSize(12)
                            .setFontColor(COLOR_ROJO_PLUS_ULTRA)
                            .setMarginTop(14)
                            .setMarginBottom(4));

                    if (servicios.isEmpty()) {
                        doc.add(new Paragraph(v.t("Sin servicios asignados", "No services assigned")).setFontSize(9).setItalic().setMarginLeft(20));
                    } else {
                        agregarResumenServicios(doc, servicios, v);
                        agregarTablaDetalleServicios(doc, servicios, v);
                    }

                    doc.add(new LineSeparator(new SolidLine(0.5f)).setMarginTop(2).setMarginBottom(6));
                }
            }

            // ═══════════════════════════════════════════════════════════════════════
            // CÓDIGO QR DE VERIFICACIÓN (uno solo para todo el grupo)
            // ═══════════════════════════════════════════════════════════════════════

            agregarCodigoQRGrupal(doc, atenciones, serviciosPorAtencion, serviciosCompartidos, v);

            // ═══════════════════════════════════════════════════════════════════════
            // FIRMA DE CONFORMIDAD (del titular del grupo — un solo PDF compartido)   ← NUEVO
            // ═══════════════════════════════════════════════════════════════════════

            agregarFirmaConformidad(doc, titular, v);

            // ═══════════════════════════════════════════════════════════════════════
            // FOOTER
            // ═══════════════════════════════════════════════════════════════════════

            doc.add(new Paragraph("\n").setFontSize(15));
            String fecha = titular.getFechaEmision() != null ?
                    titular.getFechaEmision().format(FMT_DATE) :
                    (titular.getCreatedAt() != null ?
                            titular.getCreatedAt().toLocalDate().format(FMT_DATE) :
                            java.time.LocalDate.now().format(FMT_DATE));
            doc.add(new Paragraph("Línea Aérea - " + nombreAerolinea + " - " + fecha)
                    .setFontSize(8)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontColor(COLOR_GRIS_OSCURO)
                    .setItalic());

            doc.close();

            byte[] pdfBytes = baos.toByteArray();
            log.info("✅ PDF grupal generado exitosamente: {} bytes ({} pasajeros)", pdfBytes.length, atenciones.size());
            return pdfBytes;

        } catch (Exception e) {
            log.error("❌ Error generando PDF voucher grupal: {}", e.getMessage(), e);
            throw new RuntimeException("Error generando voucher PDF grupal", e);
        }
    }

    /**
     * Código QR de verificación para el voucher GRUPAL. Codifica el PNR
     * compartido, el vuelo, la lista completa de pasajeros y — cuando los
     * servicios son compartidos — el detalle de esos servicios (si son
     * independientes por pasajero, solo referencia cuántos hay, para no
     * saturar el QR).
     */
    private void agregarCodigoQRGrupal(Document doc, List<Atencion> atenciones,
                                       Map<Long, List<ServicioAsignado>> serviciosPorAtencion,
                                       boolean serviciosCompartidos, Voz v) {
        try {
            Atencion titular = atenciones.get(0);

            doc.add(new Paragraph(v.t("CÓDIGO DE VERIFICACIÓN", "VERIFICATION CODE"))
                    .setBold()
                    .setFontSize(11)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(15));

            StringBuilder qrBuilder = new StringBuilder();
            qrBuilder.append(v.t("VOUCHER GRUPAL: ", "GROUP VOUCHER: ")).append(titular.getNumeroCorrelativo()).append("\n");
            qrBuilder.append(v.t("PASAJEROS (", "PASSENGERS (")).append(atenciones.size()).append("): ");
            qrBuilder.append(atenciones.stream()
                    .map(a -> a.getNombre() + "/" + a.getApellido())
                    .collect(Collectors.joining(", ")));
            qrBuilder.append("\n");
            qrBuilder.append(v.t("VUELO: ", "FLIGHT: ")).append(titular.getVuelo().getCodigoVuelo());
            if (titular.getFechaEmision() != null) {
                qrBuilder.append("/").append(titular.getFechaEmision().format(DateTimeFormatter.ofPattern("yyMMdd")));
            }
            qrBuilder.append("\n");
            qrBuilder.append("PNR: ").append(titular.getPnr()).append("\n");

            if (serviciosCompartidos) {
                for (ServicioAsignado sv : serviciosPorAtencion.getOrDefault(titular.getId(), List.of())) {
                    qrBuilder.append(describirServicioParaQR(sv, v)).append("\n");
                }
            } else {
                for (Atencion a : atenciones) {
                    List<ServicioAsignado> servicios = serviciosPorAtencion.getOrDefault(a.getId(), List.of());
                    qrBuilder.append(a.getNombre()).append("/").append(a.getApellido())
                            .append(": ").append(servicios.size()).append(v.t(" servicio(s)\n", " service(s)\n"));
                }
            }
            qrBuilder.append(v.t("EMITIDO: ", "ISSUED: ")).append(titular.getLugarEmision() != null ? titular.getLugarEmision() : "LIM").append("\n");
            String nombreAerolineaQR = titular.getVuelo().getAerolinea() != null
                    ? titular.getVuelo().getAerolinea() : "PLUS ULTRA";
            qrBuilder.append("AirportHub - ").append(nombreAerolineaQR);

            QRCodeWriter qrWriter = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = qrWriter.encode(qrBuilder.toString(), BarcodeFormat.QR_CODE, 200, 200, hints);

            ByteArrayOutputStream qrBaos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", qrBaos);

            Image qrImage = new Image(ImageDataFactory.create(qrBaos.toByteArray()))
                    .setWidth(150)
                    .setHeight(150)
                    .setHorizontalAlignment(HorizontalAlignment.CENTER);
            doc.add(qrImage);

            doc.add(new Paragraph(v.t(
                    "Escanee este código para verificar la autenticidad del voucher",
                    "Scan this code to verify the voucher's authenticity"))
                    .setFontSize(8)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(8)
                    .setFontColor(COLOR_GRIS_OSCURO));

        } catch (Exception qrEx) {
            log.warn("No se pudo generar QR grupal: {}", qrEx.getMessage());
        }
    }

    private String describirServicioParaQR(ServicioAsignado sv, Voz v) {
        String tipo = traducirTipoServicio(sv.getTipoDetalle(), v);
        String prov = sv.getVueloRecurso() != null && sv.getVueloRecurso().getProveedor() != null
                ? sv.getVueloRecurso().getProveedor().getNombre() : "N/A";
        StringBuilder sb = new StringBuilder(tipo).append(": ").append(prov);
        if (sv.getTipoHabitacion() != null) sb.append(" (").append(sv.getCantidad()).append("x").append(traducirTipoHabitacion(sv.getTipoHabitacion(), v)).append(")");
        if (sv.getTipoTransporte() != null) sb.append(" (").append(sv.getCantidad()).append(" PAX - ").append(traducirTipoTransporte(sv.getTipoTransporte(), v)).append(")");
        if (sv.getTipoDetalle() == TipoDetalleEnum.RESTAURANTE) sb.append(" (").append(sv.getCantidad()).append(v.t(" cubiertos)", " covers)"));
        return sb.toString();
    }

    /**
     * Agrega resumen de servicios asignados.
     */
    private void agregarResumenServicios(Document doc, List<ServicioAsignado> servicios, Voz v) {
        Map<TipoDetalleEnum, List<ServicioAsignado>> serviciosPorTipo = servicios.stream()
                .collect(Collectors.groupingBy(ServicioAsignado::getTipoDetalle));

        // ── HOTEL ──────────────────────────────────────────────────────────
        List<ServicioAsignado> serviciosHotel = serviciosPorTipo.get(TipoDetalleEnum.HOTEL);
        if (serviciosHotel != null && !serviciosHotel.isEmpty()) {
            ServicioAsignado hotel = serviciosHotel.get(0);
            String nombreHotel = obtenerNombreProveedor(hotel);

            doc.add(new Paragraph("HOTEL: " + nombreHotel.toUpperCase())
                    .setBold().setFontSize(11).setMarginTop(5));

            // ── AGREGAR ESTO ──────────────────────────────────────────────
            if (hotel.getFechaIngreso() != null || hotel.getFechaSalida() != null) {
                String checkIn  = hotel.getFechaIngreso() != null
                        ? hotel.getFechaIngreso().format(FMT_DATE)  : "—";
                String checkOut = hotel.getFechaSalida() != null
                        ? hotel.getFechaSalida().format(FMT_DATE) : "—";
                doc.add(new Paragraph("CHECK-IN: " + checkIn + "   |   CHECK-OUT: " + checkOut)
                        .setBold()
                        .setFontSize(10)
                        .setFontColor(COLOR_ROJO_PLUS_ULTRA)
                        .setMarginTop(3)
                        .setMarginBottom(3));
            }
            // ─────────────────────────────────────────────────────────────

            doc.add(new Paragraph(v.t("HABITACIONES:", "ROOMS:"))
                    .setBold().setFontSize(9).setMarginTop(4));

            for (ServicioAsignado s : serviciosHotel) {
                String tipoHab = traducirTipoHabitacion(
                        s.getTipoHabitacion() != null ? s.getTipoHabitacion() : "SIMPLE", v);
                doc.add(new Paragraph(s.getCantidad() + v.t(" HABITACION(ES) ", " ROOM(S) ") + tipoHab + "(S)")
                        .setFontSize(9).setMarginLeft(20));
            }

            // Servicios del hotel (desayuno/almuerzo/cena/snack por habitación)
            int desH = 0, almH = 0, snkH = 0, cenH = 0;
            for (ServicioAsignado s : serviciosHotel) {
                if (Boolean.TRUE.equals(s.getDesayuno()))  desH += s.getCantidad();
                if (Boolean.TRUE.equals(s.getAlmuerzo()))  almH += s.getCantidad();
                if (Boolean.TRUE.equals(s.getSnack()))     snkH += s.getCantidad();
                if (Boolean.TRUE.equals(s.getCena()))      cenH += s.getCantidad();
            }
            if (desH > 0 || almH > 0 || snkH > 0 || cenH > 0) {
                doc.add(new Paragraph(v.t("SERVICIOS INCLUIDOS:", "SERVICES INCLUDED:"))
                        .setBold().setFontSize(9).setMarginTop(4));
                if (desH > 0) doc.add(new Paragraph(desH + v.t(" DESAYUNO(S)", " BREAKFAST(S)")).setFontSize(9).setMarginLeft(20));
                if (almH > 0) doc.add(new Paragraph(almH + v.t(" ALMUERZO(S)", " LUNCH(ES)")).setFontSize(9).setMarginLeft(20));
                if (snkH > 0) doc.add(new Paragraph(snkH + " SNACK(S)").setFontSize(9).setMarginLeft(20));
                if (cenH > 0) doc.add(new Paragraph(cenH + v.t(" CENA(S)", " DINNER(S)")).setFontSize(9).setMarginLeft(20));
            }
            doc.add(new Paragraph("\n").setFontSize(4));
        }

        // ── TRANSPORTE ─────────────────────────────────────────────────────
        List<ServicioAsignado> serviciosTransporte = serviciosPorTipo.get(TipoDetalleEnum.TRANSPORTE);
        if (serviciosTransporte != null && !serviciosTransporte.isEmpty()) {
            ServicioAsignado trans = serviciosTransporte.get(0);
            String nombreTrans = obtenerNombreProveedor(trans);

            doc.add(new Paragraph(v.t("TRANSPORTE: ", "TRANSPORTATION: ") + nombreTrans.toUpperCase())
                    .setBold().setFontSize(11).setMarginTop(5));

            doc.add(new Paragraph(v.t("PASAJEROS:", "PASSENGERS:"))
                    .setBold().setFontSize(9).setMarginTop(4));

            String tipoTrans = traducirTipoTransporte(trans.getTipoTransporte(), v);
            doc.add(new Paragraph(trans.getCantidad() + v.t(" PASAJERO(S) - TIPO: ", " PASSENGER(S) - TYPE: ") + tipoTrans)
                    .setFontSize(9).setMarginLeft(20));
            doc.add(new Paragraph("\n").setFontSize(4));
        }

        // ── RESTAURANTE ────────────────────────────────────────────────────
        List<ServicioAsignado> serviciosRest = serviciosPorTipo.get(TipoDetalleEnum.RESTAURANTE);
        if (serviciosRest != null && !serviciosRest.isEmpty()) {
            ServicioAsignado rest = serviciosRest.get(0);
            String nombreRest = obtenerNombreProveedor(rest);

            doc.add(new Paragraph(v.t("RESTAURANTE: ", "RESTAURANT: ") + nombreRest.toUpperCase())
                    .setBold().setFontSize(11).setMarginTop(5));

            doc.add(new Paragraph(v.t("PAX:", "PAX:"))
                    .setBold().setFontSize(9).setMarginTop(4));
            doc.add(new Paragraph(rest.getCantidad() + v.t(" PASAJERO(S)", " PASSENGER(S)"))
                    .setFontSize(9).setMarginLeft(20));

            int desR = 0, almR = 0, cenR = 0;
            for (ServicioAsignado s : serviciosRest) {
                if (Boolean.TRUE.equals(s.getDesayuno()))  desR += s.getCantidad();
                if (Boolean.TRUE.equals(s.getAlmuerzo()))  almR += s.getCantidad();
                if (Boolean.TRUE.equals(s.getCena()))      cenR += s.getCantidad();
            }
            if (desR > 0 || almR > 0 || cenR > 0) {
                doc.add(new Paragraph(v.t("SERVICIOS:", "SERVICES:"))
                        .setBold().setFontSize(9).setMarginTop(4));
                if (desR > 0) doc.add(new Paragraph(desR + v.t(" DESAYUNO(S)", " BREAKFAST(S)")).setFontSize(9).setMarginLeft(20));
                if (almR > 0) doc.add(new Paragraph(almR + v.t(" ALMUERZO(S)", " LUNCH(ES)")).setFontSize(9).setMarginLeft(20));
                if (cenR > 0) doc.add(new Paragraph(cenR + v.t(" CENA(S)", " DINNER(S)")).setFontSize(9).setMarginLeft(20));
            }
            doc.add(new Paragraph("\n").setFontSize(4));
        }

        doc.add(new Paragraph("\n").setFontSize(6));
    }
    /**
     * Agrega tabla detallada de servicios con proveedores.
     */
    private void agregarTablaDetalleServicios(Document doc, List<ServicioAsignado> servicios, Voz v) {
        doc.add(new Paragraph(v.t("DETALLE DE SERVICIOS ASIGNADOS", "ASSIGNED SERVICES DETAIL"))
                .setBold()
                .setFontSize(11)
                .setMarginBottom(8));

        // Tabla con 4 columnas
        float[] columnWidths = {1.5f, 2.5f, 3f, 2f};
        Table tabla = new Table(UnitValue.createPercentArray(columnWidths))
                .useAllAvailableWidth();

        // Headers
        tabla.addHeaderCell(createHeaderCell(v.t("Servicio", "Service")));
        tabla.addHeaderCell(createHeaderCell(v.t("Proveedor", "Provider")));
        tabla.addHeaderCell(createHeaderCell(v.t("Detalles", "Details")));
        tabla.addHeaderCell(createHeaderCell(v.t("Contacto", "Contact")));

        // Agrupar servicios por tipo para tabla
        Map<TipoDetalleEnum, List<ServicioAsignado>> serviciosPorTipo = servicios.stream()
                .collect(Collectors.groupingBy(ServicioAsignado::getTipoDetalle));

        // HOTEL
        agregarFilaServicio(tabla, "Hotel", serviciosPorTipo.get(TipoDetalleEnum.HOTEL), v);

        // TRANSPORTE
        agregarFilaServicio(tabla, v.t("Transporte", "Transportation"), serviciosPorTipo.get(TipoDetalleEnum.TRANSPORTE), v);

        // RESTAURANTE (Alimentación)
        agregarFilaServicio(tabla, v.t("Alimentación", "Meals"), serviciosPorTipo.get(TipoDetalleEnum.RESTAURANTE), v);

        doc.add(tabla);
        doc.add(new Paragraph("\n").setFontSize(10));
    }

    /**
     * Agrega una fila de servicio a la tabla de detalles.
     */
    private void agregarFilaServicio(Table tabla, String tipoServicio, List<ServicioAsignado> servicios, Voz v) {
        if (servicios == null || servicios.isEmpty()) {
            return;
        }

        ServicioAsignado servicio = servicios.get(0);
        VueloRecurso recurso = servicio.getVueloRecurso();

        if (recurso == null) {
            return;
        }

        Proveedor proveedor = recurso.getProveedor();

        // Columna Servicio
        tabla.addCell(createDataCell(tipoServicio));

        // Columna Proveedor
        tabla.addCell(createDataCell(proveedor.getNombre()));

        // Columna Detalles
        String detalles = construirDetalles(servicio, proveedor, v);
        tabla.addCell(createDataCell(detalles));

        // Columna Contacto
        String contacto = proveedor.getTelefono() != null ? proveedor.getTelefono() : "-";
        tabla.addCell(createDataCell(contacto));
    }

    /**
     * Construye string de detalles del servicio.
     */
    private String construirDetalles(ServicioAsignado servicio, Proveedor proveedor, Voz v) {
        StringBuilder detalles = new StringBuilder();

        // Dirección del proveedor
        if (proveedor.getDireccion() != null && !proveedor.getDireccion().isBlank()) {
            detalles.append(proveedor.getDireccion());
        }

        // ── AGREGAR ESTO ──────────────────────────────────────────────
        if (servicio.getTipoDetalle() == TipoDetalleEnum.HOTEL) {
            if (servicio.getFechaIngreso() != null || servicio.getFechaSalida() != null) {
                String checkIn  = servicio.getFechaIngreso() != null
                        ? servicio.getFechaIngreso().format(FMT_DATE) : "—";
                String checkOut = servicio.getFechaSalida() != null
                        ? servicio.getFechaSalida().format(FMT_DATE) : "—";
                if (detalles.length() > 0) detalles.append("\n");
                detalles.append("Check-in: ").append(checkIn)
                        .append(" | Check-out: ").append(checkOut);
            }
        }
        // ─────────────────────────────────────────────────────────────

        // Detalles específicos por tipo
        if (servicio.getTipoDetalle() == TipoDetalleEnum.TRANSPORTE) {
            if (servicio.getTipoTransporte() != null) {
                if (detalles.length() > 0) detalles.append(" - ");
                detalles.append(v.t("Tipo: ", "Type: ")).append(traducirTipoTransporte(servicio.getTipoTransporte(), v));
            }
        }

        // Si no hay detalles, usar descripción del proveedor
        if (detalles.length() == 0 && proveedor.getDireccion() != null) {
            detalles.append(proveedor.getDireccion());
        }

        return detalles.toString().isBlank() ? "-" : detalles.toString();
    }

    /**
     * Agrega al PDF (voucher individual o grupal) la firma digital de
     * conformidad guardada en Atencion.firmaPasajero — SOLO cuando quien
     * firmó fue el propio pasajero (origenFirma = PASAJERO, o null para
     * datos legados). firmaPasajero en ese caso es una imagen PNG en
     * base64 ("data:image/png;base64,...") capturada en el canvas del
     * agente → se embebe como imagen.
     *
     * REGLA DE NEGOCIO — origenFirma = AGENTE_LOTE (carga masiva):
     * firmaPasajero guarda el nombre de quien AUTORIZÓ la carga masiva
     * completa (no es la firma del pasajero). Ese dato es un registro de
     * auditoría interno — se muestra en el detalle del reporte
     * (ReporteDetallePage), pero NUNCA debe imprimirse en el PDF del
     * voucher, ni en la generación inicial ni al reenviar desde el
     * reporte de detalle: el pasajero de una carga masiva no firmó nada
     * personalmente, y mostrar el nombre del agente ahí generaba
     * confusión. Por eso esta función corta de inmediato en ese caso.
     *
     * Si no hay firma registrada, no agrega nada (no rompe vouchers
     * antiguos sin conformidad).
     */
    private void agregarFirmaConformidad(Document doc, Atencion atencion, Voz v) {
        String firma = atencion.getFirmaPasajero();
        if (firma == null || firma.isBlank()) return;
        if (atencion.getOrigenFirma() == OrigenFirmaEnum.AGENTE_LOTE) return;

        //boolean esCargaMasiva = atencion.getOrigenFirma() == OrigenFirmaEnum.AGENTE_LOTE;

        doc.add(new Paragraph("\n").setFontSize(8));

        doc.add(new Paragraph(v.t("Firma digital de conformidad:", "Digital signature of consent:"))
                .setFontSize(9)
                .setBold()
                .setFontColor(new DeviceRgb(109, 40, 217))
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(3));

        if (    firma.startsWith("data:image")) {
            // Nombre del pasajero encima de la firma, con el mismo estilo legible
            // (bold, mayúsculas, con espaciado) usado en el detalle del reporte —
            // ya no la itálica corrida que se confundía con el resto del texto.
            String nombreCompleto = String.format("%s %s",
                            atencion.getNombre() != null ? atencion.getNombre() : "",
                            atencion.getApellido() != null ? atencion.getApellido() : "")
                    .trim();
            if (!nombreCompleto.isBlank()) {
                doc.add(new Paragraph(nombreCompleto.toUpperCase())
                        .setFontSize(10)
                        .setBold()
                        .setCharacterSpacing(0.6f)
                        .setFontColor(ColorConstants.BLACK)
                        .setTextAlignment(TextAlignment.CENTER)
                        .setMarginBottom(4));
            }

            try {
                String base64Data = firma.substring(firma.indexOf(',') + 1);
                byte[] firmaBytes = Base64.getDecoder().decode(base64Data);
                Image imgFirma = new Image(ImageDataFactory.create(firmaBytes));
                imgFirma.scaleToFit(160, 70);
                imgFirma.setHorizontalAlignment(HorizontalAlignment.CENTER);
                doc.add(imgFirma);
            } catch (Exception e) {
                log.warn("⚠️ No se pudo decodificar la firma digital para el PDF: {}", e.getMessage());
                doc.add(new Paragraph(v.t("(Firma no disponible)", "(Signature unavailable)"))
                        .setFontSize(8).setItalic()
                        .setTextAlignment(TextAlignment.CENTER));
            }
        } else {
            doc.add(new Paragraph(firma)
                    .setFontSize(11)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER));
        }

        if (atencion.getFirmaFecha() != null) {
            doc.add(new Paragraph(v.t("Firmado el ", "Signed on ") + atencion.getFirmaFecha().format(FMT))
                    .setFontSize(8)
                    .setFontColor(COLOR_GRIS_OSCURO)
                    .setTextAlignment(TextAlignment.CENTER));
        }
    }

    /**
     * Agrega código QR de verificación.
     */
    private void agregarCodigoQR(Document doc, Atencion atencion, List<ServicioAsignado> servicios, Voz v) {
        try {
            doc.add(new Paragraph(v.t("CÓDIGO DE VERIFICACIÓN", "VERIFICATION CODE"))
                    .setBold()
                    .setFontSize(11)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(15));

            // Generar QR code
            // QR contiene toda la información del voucher
            StringBuilder qrBuilder = new StringBuilder();
            qrBuilder.append("VOUCHER: ").append(atencion.getNumeroCorrelativo()).append("\n");
            qrBuilder.append(v.t("PASAJERO: ", "PASSENGER: ")).append(atencion.getNombre()).append("/").append(atencion.getApellido()).append("\n");
            qrBuilder.append(v.t("VUELO: ", "FLIGHT: ")).append(atencion.getVuelo().getCodigoVuelo());
            if (atencion.getFechaEmision() != null) {
                qrBuilder.append("/").append(atencion.getFechaEmision().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")));
            }
            qrBuilder.append("\n");
            qrBuilder.append("PNR: ").append(atencion.getPnr()).append("\n");
            // Servicios
            for (ServicioAsignado sv : servicios) {
                String tipo = traducirTipoServicio(sv.getTipoDetalle(), v);
                String prov = sv.getVueloRecurso() != null && sv.getVueloRecurso().getProveedor() != null
                        ? sv.getVueloRecurso().getProveedor().getNombre() : "N/A";
                qrBuilder.append(tipo).append(": ").append(prov);
                if (sv.getTipoHabitacion() != null) qrBuilder.append(" (").append(sv.getCantidad()).append("x").append(traducirTipoHabitacion(sv.getTipoHabitacion(), v)).append(")");
                if (sv.getTipoTransporte() != null) qrBuilder.append(" (").append(sv.getCantidad()).append(" PAX - ").append(traducirTipoTransporte(sv.getTipoTransporte(), v)).append(")");
                if (sv.getTipoDetalle() == TipoDetalleEnum.RESTAURANTE) qrBuilder.append(" (").append(sv.getCantidad()).append(v.t(" cubiertos)", " covers)"));
                qrBuilder.append("\n");
            }
            qrBuilder.append(v.t("EMITIDO: ", "ISSUED: ")).append(atencion.getLugarEmision() != null ? atencion.getLugarEmision() : "LIM").append("\n");
            String nombreAerolineaQR = atencion.getVuelo().getAerolinea() != null
                    ? atencion.getVuelo().getAerolinea() : "PLUS ULTRA";
            qrBuilder.append("AirportHub - ").append(nombreAerolineaQR);
            String qrData = qrBuilder.toString();
            QRCodeWriter qrWriter = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix matrix = qrWriter.encode(qrData, BarcodeFormat.QR_CODE, 200, 200, hints);

            ByteArrayOutputStream qrBaos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", qrBaos);

            // Agregar imagen QR
            Image qrImage = new Image(ImageDataFactory.create(qrBaos.toByteArray()))
                    .setWidth(150)
                    .setHeight(150)
                    .setHorizontalAlignment(HorizontalAlignment.CENTER);

            doc.add(qrImage);

            // Texto explicativo
            doc.add(new Paragraph(v.t(
                    "Escanee este código para verificar la autenticidad del voucher",
                    "Scan this code to verify the voucher's authenticity"))
                    .setFontSize(8)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(8)
                    .setFontColor(COLOR_GRIS_OSCURO));

        } catch (Exception qrEx) {
            log.warn("No se pudo generar QR: {}", qrEx.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MÉTODOS AUXILIARES
    // ═══════════════════════════════════════════════════════════════════════

    private Cell createLabelCell(String text) {
        return new Cell()
                .add(new Paragraph(text).setFontSize(9).setBold())
                .setBorder(null)
                .setPadding(3);
    }

    private Cell createValueCell(String text) {
        return new Cell()
                .add(new Paragraph(text).setFontSize(9))
                .setBorder(null)
                .setPadding(3);
    }

    private Cell createHeaderCell(String text) {
        return new Cell()
                .add(new Paragraph(text).setFontSize(9).setBold().setFontColor(ColorConstants.WHITE))
                .setBackgroundColor(COLOR_ROJO_PLUS_ULTRA)
                .setPadding(5)
                .setTextAlignment(TextAlignment.CENTER);
    }

    private Cell createDataCell(String text) {
        return new Cell()
                .add(new Paragraph(text).setFontSize(8))
                .setBackgroundColor(COLOR_GRIS_TABLA)
                .setPadding(5);
    }

    private String obtenerNombreProveedor(ServicioAsignado servicio) {
        if (servicio.getVueloRecurso() != null && servicio.getVueloRecurso().getProveedor() != null) {
            return servicio.getVueloRecurso().getProveedor().getNombre();
        }

        return "PROVEEDOR NO ESPECIFICADO";
    }

    /**
     * Traduce el código interno de tipoTransporte (INDIVIDUAL/GRUPAL/AMBOS)
     * a la etiqueta visible en el voucher, en español o inglés según v. Los
     * códigos internos NO cambian (se mantiene compatibilidad con datos
     * históricos); solo cambia el texto mostrado al usuario.
     */
    private String traducirTipoTransporte(String tipoTransporte, Voz v) {
        if (tipoTransporte == null) return v.t("AEROPUERTO - DOMICILIO", "AIRPORT - HOME");
        return switch (tipoTransporte.toUpperCase()) {
            case "INDIVIDUAL" -> v.t("AEROPUERTO - DOMICILIO", "AIRPORT - HOME");
            case "GRUPAL" -> v.t("DOMICILIO - AEROPUERTO", "HOME - AIRPORT");
            case "AMBOS" -> v.t(
                    "AEROPUERTO - DOMICILIO Y DOMICILIO - AEROPUERTO",
                    "AIRPORT - HOME AND HOME - AIRPORT");
            default -> tipoTransporte;
        };
    }

    /**
     * NUEVO — Traduce el tipo de habitación (SIMPLE/DOBLE/MATRIMONIAL) al
     * idioma del voucher. El valor almacenado en BD no cambia.
     */
    private String traducirTipoHabitacion(String tipoHabitacion, Voz v) {
        if (tipoHabitacion == null) return v.t("SIMPLE", "SINGLE");
        return switch (tipoHabitacion.toUpperCase()) {
            case "SIMPLE" -> v.t("SIMPLE", "SINGLE");
            case "DOBLE" -> v.t("DOBLE", "DOUBLE");
            case "MATRIMONIAL" -> v.t("MATRIMONIAL", "MATRIMONIAL (DOUBLE BED)");
            default -> tipoHabitacion;
        };
    }

    /**
     * NUEVO — Traduce el tipo de servicio (HOTEL/TRANSPORTE/RESTAURANTE),
     * usado en el contenido del QR.
     */
    private String traducirTipoServicio(TipoDetalleEnum tipo, Voz v) {
        return switch (tipo) {
            case HOTEL -> "HOTEL";
            case TRANSPORTE -> v.t("TRANSPORTE", "TRANSPORTATION");
            case RESTAURANTE -> v.t("RESTAURANTE", "RESTAURANT");
        };
    }
}
