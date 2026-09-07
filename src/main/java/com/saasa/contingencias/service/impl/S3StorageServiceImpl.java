package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.service.IS3StorageService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import java.time.Duration;

import java.time.LocalDate;

@Service
public class S3StorageServiceImpl implements IS3StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageServiceImpl.class);
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    @Value("${aws.s3.prefix}")   // ← agregar esto
    private String prefix;

    /**
     * Duración de las URLs firmadas (15 minutos)
     */
    private static final Duration PRESIGNED_URL_DURATION = Duration.ofMinutes(15);

    /**
     * Número de reintentos para operaciones fallidas
     */
    private static final int MAX_RETRIES = 3;

    public S3StorageServiceImpl(S3Client s3Client, S3Presigner s3Presigner) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    // ← CAMBIO 3: Agregar este método
    /**
     * ✅ Se ejecuta automáticamente al crear el bean
     */
    @PostConstruct
    public void inicializar() {
        log.info("🚀 [S3] Inicializando S3StorageService | bucket: {} | prefix: {}",
        bucketName, prefix);   // ← agregar prefix aquí
        boolean bucketOk = verificarBucket();

        if (bucketOk) {
            log.info("✅ [S3] Sistema S3 listo para usar");
        } else {
            log.warn("⚠️ [S3] Bucket no accesible, verifica credenciales AWS");
        }
    }


    /**
     * ✅ SUBIR PDF a S3
     *
     * @param pdfBytes Array de bytes del PDF
     * @param fileName Nombre del archivo (ej: SGC-000001000.pdf)
     * @return Key del archivo en S3 (ej: vouchers/2025/SGC-000001000.pdf)
     */
    @Override
    public String subirPdf(byte[] pdfBytes, String fileName) {
        String key = buildS3Key(fileName);

        log.info("☁️ [S3] Subiendo PDF a S3: {}", key);

        try {
            // Crear request con metadata
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("application/pdf")
                    .contentLength((long) pdfBytes.length)
                    .build();

            // Subir con reintentos
            uploadWithRetry(putRequest, pdfBytes);

            log.info("✅ [S3] PDF subido exitosamente: s3://{}/{}", bucketName, key);
            log.info("📊 [S3] Tamaño: {} KB", pdfBytes.length / 1024);

            // Retornar SOLO la key (no la URL completa)
            return key;

        } catch (Exception e) {
            log.error("❌ [S3] Error al subir PDF: {}", e.getMessage(), e);
            throw new RuntimeException("Error al subir PDF a S3: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ DESCARGAR PDF desde S3
     *
     * @param s3Key Key del archivo en S3 (ej: vouchers/2025/SGC-001.pdf)
     * @return Array de bytes del PDF
     */
    @Override
    public byte[] descargarPdf(String s3Key) {
        log.info("📥 [S3] Descargando PDF desde S3: {}", s3Key);

        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            ResponseBytes<GetObjectResponse> responseBytes =
                    s3Client.getObjectAsBytes(getRequest);

            byte[] pdfBytes = responseBytes.asByteArray();

            log.info("✅ [S3] PDF descargado: {} KB", pdfBytes.length / 1024);

            return pdfBytes;

        } catch (NoSuchKeyException e) {
            log.error("❌ [S3] Archivo no encontrado: {}", s3Key);
            throw new RuntimeException("Archivo no encontrado en S3: " + s3Key);

        } catch (Exception e) {
            log.error("❌ [S3] Error al descargar PDF: {}", e.getMessage(), e);
            throw new RuntimeException("Error al descargar PDF desde S3", e);
        }
    }

    /**
     * ✅ ELIMINAR PDF de S3
     *
     * @param s3Key Key del archivo en S3
     */
    @Override
    public void eliminarPdf(String s3Key) {
        log.info("🗑️ [S3] Eliminando PDF de S3: {}", s3Key);

        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            s3Client.deleteObject(deleteRequest);

            log.info("✅ [S3] PDF eliminado: {}", s3Key);

        } catch (Exception e) {
            log.error("❌ [S3] Error al eliminar PDF: {}", e.getMessage(), e);
            throw new RuntimeException("Error al eliminar PDF de S3", e);
        }
    }

    /**
     * ✅ GENERAR URL FIRMADA TEMPORAL para descarga
     *
     * Este método genera una URL firmada que permite descargar el PDF
     * sin necesidad de autenticación por un tiempo limitado (15 minutos).
     *
     * @param s3Key Key del archivo en S3
     * @return URL firmada temporal (válida por 15 minutos)
     */
    public String generarUrlFirmada(String s3Key) {
        log.info("🔗 [S3] Generando URL firmada para: {}", s3Key);

        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(PRESIGNED_URL_DURATION)
                    .getObjectRequest(getRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest =
                    s3Presigner.presignGetObject(presignRequest);

            String url = presignedRequest.url().toString();

            log.info("✅ [S3] URL firmada generada (válida por {} minutos)",
                    PRESIGNED_URL_DURATION.toMinutes());

            return url;

        } catch (Exception e) {
            log.error("❌ [S3] Error al generar URL firmada: {}", e.getMessage(), e);
            throw new RuntimeException("Error al generar URL de descarga", e);
        }
    }

    @Override
    public String generarUrlConExpiracion(String objectKey, int diasExpiracion) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofDays(diasExpiracion))
                .getObjectRequest(getObjectRequest)
                .build();

        // DESPUÉS (usa el bean ya inyectado en el constructor):
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * ✅ SUBIR OBJETO genérico a una key exacta (sin el "vouchers/{año}/"
     * de subirPdf) — usado para assets como el logo de una aerolínea.
     *
     * Solo se le antepone el prefijo de ambiente ({@code prefix}), igual
     * que buildS3Key, así que la key resultante es {@code prefix + objectKey}.
     * Esa es la key que hay que guardar como referencia (ej. logoKey) —
     * nunca reconstruirla por separado, para no desincronizarla de la
     * key real en S3.
     */
    @Override
    public String subirObjeto(byte[] bytes, String objectKey, String contentType) {
        String key = prefix + objectKey;

        log.info("☁️ [S3] Subiendo objeto a S3: {}", key);

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .contentLength((long) bytes.length)
                    .build();

            uploadWithRetry(putRequest, bytes);

            log.info("✅ [S3] Objeto subido exitosamente: s3://{}/{}", bucketName, key);
            return key;

        } catch (Exception e) {
            log.error("❌ [S3] Error al subir objeto: {}", e.getMessage(), e);
            throw new RuntimeException("Error al subir objeto a S3: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ CONSTRUIR KEY de S3 con estructura organizada
     *
     * Estructura: vouchers/{año}/{nombre_archivo}
     * Ejemplo: vouchers/2025/SGC-000001000.pdf
     *
     * @param fileName Nombre del archivo
     * @return Key completa para S3
     */
    private String buildS3Key(String fileName) {
        int currentYear = LocalDate.now().getYear();
        return String.format("%svouchers/%d/%s", prefix, currentYear, fileName);
    }

    /**
     * ✅ SUBIR CON REINTENTOS
     *
     * Implementa lógica de reintento para operaciones de subida
     * que puedan fallar temporalmente.
     *
     * @param putRequest Request de subida
     * @param pdfBytes Bytes del PDF
     */
    private void uploadWithRetry(PutObjectRequest putRequest, byte[] pdfBytes) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < MAX_RETRIES) {
            try {
                s3Client.putObject(putRequest, RequestBody.fromBytes(pdfBytes));
                return; // Éxito, salir

            } catch (Exception e) {
                lastException = e;
                attempt++;

                if (attempt < MAX_RETRIES) {
                    log.warn("⚠️ [S3] Intento {}/{} falló, reintentando...",
                            attempt, MAX_RETRIES);

                    try {
                        Thread.sleep(1000 * attempt); // Backoff exponencial
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // Si llegamos aquí, todos los reintentos fallaron
        log.error("❌ [S3] Todos los reintentos fallaron después de {} intentos",
                MAX_RETRIES);
        throw new RuntimeException("Error al subir PDF después de " + MAX_RETRIES +
                " intentos", lastException);
    }

    /**
     * ✅ VERIFICAR SI EL BUCKET EXISTE
     *
     * Útil para validar la configuración al inicio
     */
    public boolean verificarBucket() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder()
                    .bucket(bucketName)
                    .build());

            log.info("✅ [S3] Bucket verificado: {}", bucketName);
            return true;

        } catch (Exception e) {
            log.error("❌ [S3] Error verificando bucket: {}", e.getMessage());
            return false;
        }
    }
}
