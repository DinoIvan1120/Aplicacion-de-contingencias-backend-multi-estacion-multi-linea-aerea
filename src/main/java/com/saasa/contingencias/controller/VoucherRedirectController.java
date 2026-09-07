package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.repository.AtencionRepository;
import com.saasa.contingencias.service.IS3StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
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
 */
@RestController
@RequestMapping("/v")
@Slf4j
public class VoucherRedirectController {

    private final AtencionRepository atencionRepository;
    private final IS3StorageService s3StorageService;

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
