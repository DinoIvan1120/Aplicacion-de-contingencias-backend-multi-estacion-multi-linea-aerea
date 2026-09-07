package com.saasa.contingencias.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Controller para servir archivos PDF en modo desarrollo local.
 *
 * Solo se activa con perfil 'local'.
 * Permite descargar PDFs guardados localmente.
 *
 * Endpoints:
 * - GET /api/v1/files/{fileName} - Descarga PDF por nombre
 * - GET /api/v1/files/vouchers/{fileName} - Descarga voucher
 *
 * @author SAASA Development Team
 */
@RestController
@RequestMapping("/api/v1/files")
@Profile("dev")
@Slf4j
public class FileDownloadController {

    @Value("${app.storage.local.directory:${user.home}/saasa-pdfs}")
    private String localStorageDirectory;

    /**
     * Descarga archivo PDF desde almacenamiento local.
     *
     * Ejemplos:
     * - GET /api/v1/files/vouchers/SGC-000001000.pdf
     * - GET /api/v1/files/test.pdf
     */
    @GetMapping("/**")
    public ResponseEntity<Resource> downloadFile(
            jakarta.servlet.http.HttpServletRequest request) {

        try {
            // Extraer path completo después de /api/v1/files/
            String fullPath = request.getRequestURI().substring("/api/v1/files/".length());

            log.info("📥 Solicitud de descarga: {}", fullPath);

            // Construir path absoluto
            Path filePath = Paths.get(localStorageDirectory, fullPath);
            File file = filePath.toFile();

            if (!file.exists()) {
                log.error("❌ Archivo no encontrado: {}", filePath.toAbsolutePath());
                return ResponseEntity.notFound().build();
            }

            if (!file.canRead()) {
                log.error("❌ No se puede leer el archivo: {}", filePath.toAbsolutePath());
                return ResponseEntity.status(403).build();
            }

            log.info("✅ Sirviendo archivo: {} ({} bytes)",
                    filePath.toAbsolutePath(), file.length());

            Resource resource = new FileSystemResource(file);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + file.getName() + "\"")
                    .body(resource);

        } catch (Exception e) {
            log.error("❌ Error al servir archivo: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}

