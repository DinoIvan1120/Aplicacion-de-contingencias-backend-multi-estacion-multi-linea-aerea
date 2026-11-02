package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.repository.AtencionRepository;
import com.saasa.contingencias.service.IS3StorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;                                          // ← NUEVO
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Endpoint público de redirección para los enlaces de voucher enviados por
 * WhatsApp / correo.
 *
 * <p><b>Por qué existe:</b> WhatsApp no soporta hipervínculos con texto ancla
 * en mensajes de texto plano — solo puede mostrar la URL "cruda". Enviar la
 * URL firmada de S3 directamente expone el nombre del bucket, la firma AWS
 * y hace el mensaje poco profesional.</p>
 *
 * <p>En su lugar, se envía un enlace corto y estable:</p>
 * <pre>https://vouchers.saasa.pe/v/SGC-000000236</pre>
 *
 * <p>Este controller resuelve ese enlace al vuelo: busca la atención por su
 * código de correlativo, genera una URL firmada de S3 <b>en el momento del
 * clic</b> (no al enviar el mensaje) y redirige (302) al PDF real.</p>
 *
 * <p>Ventaja de seguridad adicional: al generar la URL firmada recién cuando
 * el usuario hace clic, esta puede tener una vigencia corta (15 minutos)
 * sin afectar la experiencia — el enlace corto en sí no vence.</p>
 *
 * <p><b>QR del ticket térmico — /v/{correlativo}/ver:</b> los lectores de QR
 * suelen abrir el enlace en una pestaña integrada (p. ej. el lector de
 * Xiaomi), que no muestra un PDF recibido por redirección y queda en blanco.
 * Para el QR se usa una página que DIBUJA el PDF dentro de ella con PDF.js
 * (leyéndolo de /v/{correlativo}/pdf, mismo dominio), con botones
 * "Descargar PDF" y, en Android, "Abrir en Chrome" como respaldo.
 * El enlace de WhatsApp (/v/{correlativo}) no cambia.</p>
 */
@RestController
@RequestMapping("/v")
@Slf4j
public class VoucherRedirectController {

    private final AtencionRepository atencionRepository;
    private final IS3StorageService s3StorageService;

    /** Base pública (la misma del enlace de WhatsApp); se usa para el botón "Abrir en Chrome". */
    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    public VoucherRedirectController(AtencionRepository atencionRepository,
                                     IS3StorageService s3StorageService) {
        this.atencionRepository = atencionRepository;
        this.s3StorageService = s3StorageService;
    }

    /**
     * Resuelve el enlace corto del voucher y redirige al PDF real en S3.
     *
     * GET /v/{correlativo}  →  302 Found  →  Location: <url firmada S3>
     *
     * Si el correlativo no existe o la atención aún no tiene PDF generado,
     * responde 404 con una página HTML simple (el usuario final ve esto en
     * su navegador, no un cliente de API, así que no se usa ApiResponse/JSON).
     */
    @GetMapping("/{correlativo}")
    public ResponseEntity<?> resolverVoucher(@PathVariable String correlativo) {

        Optional<Atencion> atencionOpt = atencionRepository.findByNumeroCorrelativo(correlativo);

        if (atencionOpt.isEmpty() || atencionOpt.get().getPdfUrl() == null
                || atencionOpt.get().getPdfUrl().isBlank()) {
            log.warn("⚠️ [VoucherRedirect] Enlace no válido o voucher no disponible: {}", correlativo);
            return notFoundHtml();
        }

        Atencion atencion = atencionOpt.get();

        try {
            String urlFirmada = s3StorageService.generarUrlFirmada(atencion.getPdfUrl());
            log.info("🔗 [VoucherRedirect] Redirigiendo {} → S3 (URL firmada, 15 min)", correlativo);

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(urlFirmada))
                    .build();

        } catch (Exception e) {
            log.error("❌ [VoucherRedirect] Error generando URL firmada para {}: {}",
                    correlativo, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.TEXT_HTML)
                    .body(paginaError("No pudimos abrir tu voucher en este momento. "
                            + "Inténtalo nuevamente en unos minutos."));
        }
    }

    /**
     * PDF del voucher servido desde el mismo dominio: GET /v/{correlativo}/pdf
     *
     * Lo usa el visor de la página /ver (PDF.js necesita leer el archivo desde
     * el mismo origen; la URL firmada de S3 es de otro dominio y requeriría CORS).
     * Con ?descargar=true se envía como adjunto para guardarlo en el celular.
     */
    @GetMapping("/{correlativo}/pdf")
    public ResponseEntity<?> pdfVoucher(@PathVariable String correlativo,
                                        @RequestParam(defaultValue = "false") boolean descargar) {

        Optional<Atencion> atencionOpt = atencionRepository.findByNumeroCorrelativo(correlativo);

        if (atencionOpt.isEmpty() || atencionOpt.get().getPdfUrl() == null
                || atencionOpt.get().getPdfUrl().isBlank()) {
            log.warn("⚠️ [VoucherRedirect] PDF no disponible: {}", correlativo);
            return notFoundHtml();
        }

        try {
            byte[] pdf = s3StorageService.descargarPdf(atencionOpt.get().getPdfUrl());
            String nombreArchivo = correlativo.replaceAll("[^A-Za-z0-9_-]", "_") + ".pdf";
            ContentDisposition disposicion = (descargar
                    ? ContentDisposition.attachment()
                    : ContentDisposition.inline())
                    .filename(nombreArchivo)
                    .build();

            log.info("📄 [VoucherRedirect] PDF servido ({}): {}", descargar ? "descarga" : "visor", correlativo);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposicion.toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(pdf);

        } catch (Exception e) {
            log.error("❌ [VoucherRedirect] Error obteniendo PDF de {}: {}", correlativo, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.TEXT_HTML)
                    .body(paginaError("No pudimos abrir tu voucher en este momento. "
                            + "Inténtalo nuevamente en unos minutos."));
        }
    }

    /**
     * Página del QR del ticket: GET /v/{correlativo}/ver
     *
     * Muestra el PDF del voucher DENTRO de la página (dibujado con PDF.js), así
     * se ve igual en Chrome, en las pestañas integradas de los lectores de QR
     * (Xiaomi, etc.) y en Safari, sin depender del visor de PDF del celular.
     * Incluye "Descargar PDF" y, en Android, "Abrir en Chrome" como respaldo.
     */
    @GetMapping("/{correlativo}/ver")
    public ResponseEntity<String> paginaVoucher(@PathVariable String correlativo,
                                                HttpServletRequest request) {

        Optional<Atencion> atencionOpt = atencionRepository.findByNumeroCorrelativo(correlativo);

        if (atencionOpt.isEmpty() || atencionOpt.get().getPdfUrl() == null
                || atencionOpt.get().getPdfUrl().isBlank()) {
            log.warn("⚠️ [VoucherRedirect] Página de voucher no disponible: {}", correlativo);
            return notFoundHtml();
        }

        Atencion atencion = atencionOpt.get();
        String corrUrl = URLEncoder.encode(correlativo, StandardCharsets.UTF_8);
        String rutaPdf = "/v/" + corrUrl + "/pdf";
        String urlPaginaAbsoluta = baseAbsoluta(request) + "/v/" + corrUrl + "/ver";

        log.info("🔗 [VoucherRedirect] Página de voucher (QR ticket): {}", correlativo);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(paginaVoucherHtml(atencion, correlativo, rutaPdf, urlPaginaAbsoluta));
    }

    /** https://host[:puerto] — de app.public-base-url si está configurada; si no, del request. */
    private String baseAbsoluta(HttpServletRequest request) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        if (!base.isBlank()) {
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                base = "https://" + base;
            }
            return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        }
        String host = request.getServerName();
        int puerto = request.getServerPort();
        boolean puertoEstandar = puerto == 80 || puerto == 443 || puerto <= 0;
        return "https://" + host + (puertoEstandar ? "" : ":" + puerto);
    }

    private String paginaVoucherHtml(Atencion atencion, String correlativo,
                                     String rutaPdf, String urlPaginaAbsoluta) {
        String sinEsquema = urlPaginaAbsoluta.replaceFirst("^https?://", "");
        String intentChrome = "intent://" + sinEsquema
                + "#Intent;scheme=https;package=com.android.chrome;S.browser_fallback_url="
                + URLEncoder.encode(urlPaginaAbsoluta, StandardCharsets.UTF_8) + ";end";

        String pasajero = (texto(atencion.getNombre()) + " " + texto(atencion.getApellido())).trim();
        boolean grupal = atencion.getGrupoId() != null && !atencion.getGrupoId().isBlank();

        return """
                <!DOCTYPE html>
                <html lang="es">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>SAASA - Voucher %s</title>
                    <style>
                        body { font-family: Arial, sans-serif; background:#e9ecf1; margin:0; }
                        .cab { background:#0b3d91; color:#fff; padding:14px 16px; }
                        .cab h1 { font-size:17px; margin:0 0 4px; }
                        .cab .dato { font-size:13px; opacity:0.9; margin:2px 0; }
                        .acciones { display:flex; gap:8px; padding:10px 12px; flex-wrap:wrap; }
                        .btn { flex:1; min-width:140px; text-align:center; padding:12px; border-radius:10px;
                               text-decoration:none; font-weight:bold; font-size:15px; }
                        .primario { background:#0b3d91; color:#fff; }
                        .secundario { background:#fff; color:#0b3d91; border:1px solid #0b3d91; display:none; }
                        #estado { text-align:center; color:#555; padding:24px 16px; font-size:14px; }
                        #visor { max-width:900px; margin:0 auto; padding:0 8px 16px; }
                        #visor canvas { display:block; width:100%%; height:auto; background:#fff;
                                        margin:0 auto 10px; box-shadow:0 1px 6px rgba(0,0,0,0.15); }
                    </style>
                </head>
                <body>
                    <div class="cab">
                        <h1>✈️ Voucher %s</h1>
                        <div class="dato">%s</div>
                        <div class="dato">%s</div>
                    </div>
                    <div class="acciones">
                        <a class="btn primario" href="%s">Descargar PDF</a>
                        <a class="btn secundario" id="chrome" href="%s">Abrir en Chrome</a>
                    </div>
                    <div id="estado">Cargando voucher…</div>
                    <div id="visor"></div>

                    <script src="https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js"></script>
                    <script>
                    (function () {
                        var urlPdf = '%s';
                        var visor = document.getElementById('visor');
                        var estado = document.getElementById('estado');
                        var esAndroid = /Android/i.test(navigator.userAgent);
                        var esChrome = /Chrome\\//.test(navigator.userAgent) && !/wv\\)|MiuiBrowser|XiaoMi/i.test(navigator.userAgent);
                        if (esAndroid && !esChrome) {
                            document.getElementById('chrome').style.display = 'block';
                        }
                        function fallo() {
                            estado.textContent = 'No se pudo mostrar el voucher aquí. Use "Descargar PDF"'
                                + (esAndroid ? ' o "Abrir en Chrome".' : '.');
                            document.getElementById('chrome').style.display = esAndroid ? 'block' : 'none';
                        }
                        if (!window.pdfjsLib) { fallo(); return; }
                        pdfjsLib.GlobalWorkerOptions.workerSrc =
                            'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js';
                        pdfjsLib.getDocument({
                            url: urlPdf,
                            // Fuentes estándar (Helvetica, Times...) para que el texto se vea igual al original
                            standardFontDataUrl: 'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/standard_fonts/'
                        }).promise.then(function (pdf) {
                            var cadena = Promise.resolve();
                            for (var n = 1; n <= pdf.numPages; n++) {
                                (function (num) {
                                    cadena = cadena.then(function () {
                                        return pdf.getPage(num).then(function (pagina) {
                                            var ancho = visor.clientWidth || window.innerWidth;
                                            var base = pagina.getViewport({ scale: 1 });
                                            var dpr = window.devicePixelRatio || 1;
                                            var vista = pagina.getViewport({ scale: (ancho / base.width) * dpr });
                                            var lienzo = document.createElement('canvas');
                                            lienzo.width = vista.width;
                                            lienzo.height = vista.height;
                                            visor.appendChild(lienzo);
                                            return pagina.render({ canvasContext: lienzo.getContext('2d'),
                                                                   viewport: vista }).promise;
                                        });
                                    });
                                })(n);
                            }
                            return cadena.then(function () { estado.style.display = 'none'; });
                        }).catch(fallo);
                    })();
                    </script>
                </body>
                </html>
                """.formatted(
                html(correlativo),
                html(correlativo),
                html(grupal ? "Voucher grupal · Titular: " + pasajero : "Pasajero: " + pasajero),
                html(atencion.getPnr() == null ? "" : "PNR: " + atencion.getPnr()),
                html(rutaPdf + "?descargar=true"),
                html(intentChrome),
                rutaPdf);   // ya viene URL-encoded: sin comillas ni caracteres peligrosos para JS
    }

    private static String texto(String s) {
        return s == null ? "" : s;
    }

    /** Escapa texto para insertarlo en HTML. */
    private static String html(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private ResponseEntity<String> notFoundHtml() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(paginaError("Este enlace de voucher no es válido o ya no está disponible. "
                        + "Comuníquese con el personal de SAASA en el aeropuerto."));
    }

    private String paginaError(String mensaje) {
        return """
                <!DOCTYPE html>
                <html lang="es">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>SAASA - Voucher</title>
                    <style>
                        body { font-family: Arial, sans-serif; background:#f5f5f5; margin:0;
                               display:flex; align-items:center; justify-content:center; height:100vh; }
                        .card { background:#fff; padding:32px; border-radius:8px; max-width:420px;
                                text-align:center; box-shadow:0 2px 8px rgba(0,0,0,0.1); }
                        h1 { font-size:20px; color:#c0392b; }
                        p { color:#555; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <h1>✈️ SAASA – Servicios de Contingencia</h1>
                        <p>%s</p>
                    </div>
                </body>
                </html>
                """.formatted(mensaje);
    }
}
