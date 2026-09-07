package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.service.IS3StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Implementación de almacenamiento LOCAL para desarrollo.
 *
 * Guarda PDFs en el sistema de archivos local en lugar de S3.
 * Útil para desarrollo y testing sin necesidad de credenciales AWS.
 *
 *
 *
 * @author SAASA Development Team
 */
/*
@Service
@Profile("dev")
@Slf4j
public class LocalStorageServiceImpl implements IS3StorageService {

    @Value("${app.storage.local.directory:${user.home}/saasa-pdfs}")
    private String localStorageDirectory;

    @Value("${server.port:8080}")
    private String serverPort;

    @Override
    public String subirPdf(byte[] pdfBytes, String fileName) {
        try {
            log.info("💾 [LOCAL STORAGE] Guardando PDF localmente: {}", fileName);

            // Crear directorio si no existe
            Path storagePath = Paths.get(localStorageDirectory);
            if (!Files.exists(storagePath)) {
                Files.createDirectories(storagePath);
                log.info("📁 Directorio creado: {}", storagePath.toAbsolutePath());
            }

            // Guardar archivo
            Path filePath = storagePath.resolve(fileName);

            // Crear subdirectorios si es necesario (ej: vouchers/SGC-000001000.pdf)
            Path parentDir = filePath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            Files.write(filePath, pdfBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.info("✅ PDF guardado en: {}", filePath.toAbsolutePath());

            // Retornar URL local para descargar
            String localUrl = "http://localhost:" + serverPort + "/api/v1/files/" + fileName;

            log.info("🔗 URL local generada: {}", localUrl);

            return localUrl;

        } catch (IOException e) {
            log.error("❌ Error al guardar PDF localmente: {}", e.getMessage(), e);
            throw new RuntimeException("Error al guardar PDF en almacenamiento local", e);
        }
    }

    @Override
    public byte[] descargarPdf(String fileUrl) {
        try {
            log.info("📥 [LOCAL STORAGE] Descargando PDF desde: {}", fileUrl);

            // Extraer nombre de archivo de la URL
            // http://localhost:8080/api/v1/files/vouchers/SGC-000001000.pdf
            String fileName = fileUrl.substring(fileUrl.lastIndexOf("/api/v1/files/") + 14);

            Path filePath = Paths.get(localStorageDirectory, fileName);

            if (!Files.exists(filePath)) {
                log.error("❌ Archivo no encontrado: {}", filePath.toAbsolutePath());
                throw new RuntimeException("Archivo no encontrado: " + fileName);
            }

            byte[] pdfBytes = Files.readAllBytes(filePath);

            log.info("✅ PDF descargado: {} bytes", pdfBytes.length);

            return pdfBytes;

        } catch (IOException e) {
            log.error("❌ Error al descargar PDF: {}", e.getMessage(), e);
            throw new RuntimeException("Error al descargar PDF desde almacenamiento local", e);
        }
    }

    @Override
    public void eliminarPdf(String fileUrl) {
        try {
            log.info("🗑️ [LOCAL STORAGE] Eliminando PDF: {}", fileUrl);

            String fileName = fileUrl.substring(fileUrl.lastIndexOf("/api/v1/files/") + 14);
            Path filePath = Paths.get(localStorageDirectory, fileName);

            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("✅ PDF eliminado: {}", filePath.toAbsolutePath());
            } else {
                log.warn("⚠️ Archivo no encontrado para eliminar: {}", filePath.toAbsolutePath());
            }

        } catch (IOException e) {
            log.error("❌ Error al eliminar PDF: {}", e.getMessage(), e);
            throw new RuntimeException("Error al eliminar PDF desde almacenamiento local", e);
        }
    }
}

 */