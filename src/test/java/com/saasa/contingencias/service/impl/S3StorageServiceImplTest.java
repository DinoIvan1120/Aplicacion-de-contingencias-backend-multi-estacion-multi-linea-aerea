package com.saasa.contingencias.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.MalformedURLException;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceImplTest {

    @Mock S3Client s3Client;
    @Mock S3Presigner s3Presigner;

    private S3StorageServiceImpl s3StorageService;

    @BeforeEach
    void setUp() {
        // No hay constructor con bucketName/prefix (son @Value), así que se
        // inyectan por reflexión, igual que Spring lo haría en producción.
        s3StorageService = new S3StorageServiceImpl(s3Client, s3Presigner);
        ReflectionTestUtils.setField(s3StorageService, "bucketName", "saasa-test-bucket");
        ReflectionTestUtils.setField(s3StorageService, "prefix", "contingencias/");
    }

    // ══════════════════════════════════════════════════════════════════════
    // subirPdf
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void subirPdf_exitoso_retornaKeyConPrefixYAnioActual() {
        byte[] pdfBytes = "contenido-pdf".getBytes();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String key = s3StorageService.subirPdf(pdfBytes, "SGC-000001000.pdf");

        assertTrue(key.startsWith("contingencias/vouchers/"));
        assertTrue(key.endsWith("SGC-000001000.pdf"));
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void subirPdf_fallaUnaVezYReintentaConExito() {
        byte[] pdfBytes = "contenido-pdf".getBytes();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("Timeout de red"))
                .thenReturn(PutObjectResponse.builder().build());

        String key = s3StorageService.subirPdf(pdfBytes, "SGC-000002000.pdf");

        assertNotNull(key);
        // 1er intento falla, 2do intento (reintento) tiene éxito
        verify(s3Client, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void subirPdf_fallaLosTresIntentos_lanzaRuntimeExceptionConMensajeClaro() {
        byte[] pdfBytes = "contenido-pdf".getBytes();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("S3 no disponible"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> s3StorageService.subirPdf(pdfBytes, "SGC-000003000.pdf"));

        assertTrue(ex.getMessage().contains("después de 3 intentos"));
        verify(s3Client, times(3)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    // ══════════════════════════════════════════════════════════════════════
    // descargarPdf
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void descargarPdf_exitoso_retornaBytes() {
        byte[] esperado = "pdf-bytes".getBytes();
        ResponseBytes<GetObjectResponse> responseBytes =
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), esperado);
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

        byte[] resultado = s3StorageService.descargarPdf("vouchers/2026/SGC-001.pdf");

        assertArrayEquals(esperado, resultado);
    }

    // ══════════════════════════════════════════════════════════════════════
    // subirObjeto
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void subirObjeto_exitoso_retornaKeyConPrefixSinVouchersNiAnio() {
        byte[] logoBytes = "logo-bytes".getBytes();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String key = s3StorageService.subirObjeto(logoBytes, "logos/PUL.png", "image/png");

        // A diferencia de subirPdf, NO debe llevar "vouchers/{año}/" — solo
        // el prefijo de ambiente + la key relativa tal cual se le pasó.
        assertEquals("contingencias/logos/PUL.png", key);
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void subirObjeto_fallaLosTresIntentos_lanzaRuntimeExceptionConMensajeClaro() {
        byte[] logoBytes = "logo-bytes".getBytes();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("S3 no disponible"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> s3StorageService.subirObjeto(logoBytes, "logos/PUL.png", "image/png"));

        assertTrue(ex.getMessage().contains("Error al subir objeto a S3"));
        verify(s3Client, times(3)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void descargarPdf_keyInexistente_lanzaRuntimeExceptionConMensajeClaro() {
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("no existe").build());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> s3StorageService.descargarPdf("vouchers/2026/no-existe.pdf"));

        assertTrue(ex.getMessage().contains("Archivo no encontrado"));
    }

    @Test
    void descargarPdf_errorGenericoS3_lanzaRuntimeException() {
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(new RuntimeException("Error de conexión"));

        assertThrows(RuntimeException.class,
                () -> s3StorageService.descargarPdf("vouchers/2026/SGC-001.pdf"));
    }

    // ══════════════════════════════════════════════════════════════════════
    // eliminarPdf
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void eliminarPdf_exitoso_invocaDeleteObject() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        assertDoesNotThrow(() -> s3StorageService.eliminarPdf("vouchers/2026/SGC-001.pdf"));

        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void eliminarPdf_errorS3_lanzaRuntimeException() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(new RuntimeException("Error de permisos"));

        assertThrows(RuntimeException.class,
                () -> s3StorageService.eliminarPdf("vouchers/2026/SGC-001.pdf"));
    }

    // ══════════════════════════════════════════════════════════════════════
    // verificarBucket
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void verificarBucket_bucketExiste_retornaTrue() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenReturn(HeadBucketResponse.builder().build());

        assertTrue(s3StorageService.verificarBucket());
    }

    @Test
    void verificarBucket_bucketNoExiste_retornaFalse() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(NoSuchBucketException.builder().message("no existe").build());

        assertFalse(s3StorageService.verificarBucket());
    }

    // ══════════════════════════════════════════════════════════════════════
    // generarUrlConExpiracion
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void generarUrlConExpiracion_retornaUrlDelPresigner() throws MalformedURLException {
        URL urlFirmada = new URL("https://s3.amazonaws.com/bucket/key?signature=abc");
        PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
        when(presignedRequest.url()).thenReturn(urlFirmada);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenReturn(presignedRequest);

        String url = s3StorageService.generarUrlConExpiracion("vouchers/2026/SGC-001.pdf", 7);

        assertEquals("https://s3.amazonaws.com/bucket/key?signature=abc", url);
    }
}
