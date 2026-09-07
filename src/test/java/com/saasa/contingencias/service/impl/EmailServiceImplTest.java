package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.domain.enumeration.EstadoEnvioEnum;
import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.model.EnvioPdf;
import com.saasa.contingencias.domain.repository.AtencionRepository;
import com.saasa.contingencias.domain.repository.EnvioPdfRepository;
import com.saasa.contingencias.domain.repository.ServicioAsignadoRepository;
import com.saasa.contingencias.domain.repository.UsuarioRepository;
import com.saasa.contingencias.service.IPdfGeneratorService;
import com.saasa.contingencias.service.IS3StorageService;
import com.saasa.contingencias.service.IAuditoriaService;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.Multipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test unitario de EmailServiceImpl, enfocado en enviarVoucherGrupal()
 * (voucher grupal: un solo correo con un solo PDF adjunto para varios
 * pasajeros que comparten PNR/correo).
 *
 * JavaMailSender.createMimeMessage() debe devolver un MimeMessage REAL
 * (no un mock) porque MimeMessageHelper opera directamente sobre él
 * (setRecipients, setContent, etc. requieren una Session real).
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock JavaMailSender mailSender;
    @Mock AtencionRepository atencionRepository;
    @Mock EnvioPdfRepository envioPdfRepository;
    @Mock IS3StorageService s3StorageService;
    @Mock IAuditoriaService auditoriaService;
    @Mock UsuarioRepository usuarioRepository;
    @Mock ServicioAsignadoRepository servicioAsignadoRepository;
    @Mock IPdfGeneratorService pdfGeneratorService;

    @InjectMocks EmailServiceImpl emailService;

    private static final byte[] PDF_BYTES = new byte[]{1, 2, 3};

    @BeforeEach
    void setUp() {
        // @Value("${spring.mail.username}") no se inyecta con @InjectMocks —
        // hay que setearlo manualmente, igual que en producción.
        ReflectionTestUtils.setField(emailService, "fromEmail", "no-reply@airporthub.test");

        // MimeMessageHelper necesita un MimeMessage real (con Session real),
        // no un mock — createMimeMessage() debe devolver una instancia real.
        MimeMessage mimeMessage = new MimeMessage(
                jakarta.mail.Session.getInstance(new Properties()));
        lenient().when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // enviarVoucherGrupal() — envío exitoso
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Envío exitoso → llama mailSender.send() una sola vez y guarda EnvioPdf EXITOSO")
    void enviarVoucherGrupal_envioExitoso_guardaEnvioPdfConEstadoExitoso() {
        Atencion titular = Atencion.builder()
                .id(1L).numeroCorrelativo("SGC-000000001").build();
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000001"))
                .thenReturn(Optional.of(titular));

        emailService.enviarVoucherGrupal(
                "grupo@test.com",null,
                "SGC-000000001 (+1)",
                PDF_BYTES,
                List.of("Juan Perez", "Flor Vasquez"),IdiomaVoucherEnum.ES);

        // Se intenta enviar una sola vez (no hay excepción → no reintenta)
        verify(mailSender, times(1)).send(any(MimeMessage.class));

        ArgumentCaptor<EnvioPdf> captor = ArgumentCaptor.forClass(EnvioPdf.class);
        verify(envioPdfRepository).save(captor.capture());
        EnvioPdf guardado = captor.getValue();
        assertEquals(EstadoEnvioEnum.EXITOSO, guardado.getEstadoEnvio());
        assertEquals("grupo@test.com", guardado.getCorreoDestino());
        assertEquals(titular, guardado.getAtencion());
        assertEquals(1, guardado.getIntentos());
        assertNotNull(guardado.getEnviadoEn());
    }

    @Test
    @DisplayName("El correlativoGrupo con formato 'SGC-X (+N)' busca la atención por el correlativo del titular (antes del espacio)")
    void enviarVoucherGrupal_correlativoConSufijo_buscaAtencionPorCorrelativoTitular() {
        Atencion titular = Atencion.builder()
                .id(1L).numeroCorrelativo("SGC-000000001").build();
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000001"))
                .thenReturn(Optional.of(titular));

        emailService.enviarVoucherGrupal(
                "grupo@test.com",null,
                "SGC-000000001 (+2)", // ← grupo de 3 pasajeros
                PDF_BYTES,
                List.of("Juan Perez", "Flor Vasquez", "Ana Ruiz"),IdiomaVoucherEnum.ES);

        verify(atencionRepository).findByNumeroCorrelativo("SGC-000000001");
    }

    @Test
    @DisplayName("Un solo pasajero (sin sufijo '(+N)') también funciona igual")
    void enviarVoucherGrupal_unSoloPasajeroSinSufijo_funcionaIgual() {
        Atencion titular = Atencion.builder()
                .id(1L).numeroCorrelativo("SGC-000000001").build();
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000001"))
                .thenReturn(Optional.of(titular));

        emailService.enviarVoucherGrupal(
                "juan@test.com",null,
                "SGC-000000001",
                PDF_BYTES,
                List.of("Juan Perez"),IdiomaVoucherEnum.ES);

        verify(mailSender, times(1)).send(any(MimeMessage.class));
        ArgumentCaptor<EnvioPdf> captor = ArgumentCaptor.forClass(EnvioPdf.class);
        verify(envioPdfRepository).save(captor.capture());
        assertEquals(EstadoEnvioEnum.EXITOSO, captor.getValue().getEstadoEnvio());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // enviarVoucherGrupal() — reintentos y fallo
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("mailSender.send() falla siempre → reintenta MAX_EMAIL_RETRIES veces y guarda EnvioPdf FALLIDO")
    void enviarVoucherGrupal_envioFallaSiempre_reintentaYGuardaFallido() {
        Atencion titular = Atencion.builder()
                .id(1L).numeroCorrelativo("SGC-000000001").build();
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000001"))
                .thenReturn(Optional.of(titular));
        doThrow(new org.springframework.mail.MailSendException("SMTP down"))
                .when(mailSender).send(any(MimeMessage.class));

        emailService.enviarVoucherGrupal(
                "grupo@test.com",null,
                "SGC-000000001 (+1)",
                PDF_BYTES,
                List.of("Juan Perez", "Flor Vasquez"),IdiomaVoucherEnum.ES);

        // MAX_EMAIL_RETRIES = 3 → intenta exactamente 3 veces
        verify(mailSender, times(3)).send(any(MimeMessage.class));

        ArgumentCaptor<EnvioPdf> captor = ArgumentCaptor.forClass(EnvioPdf.class);
        verify(envioPdfRepository).save(captor.capture());
        EnvioPdf guardado = captor.getValue();
        assertEquals(EstadoEnvioEnum.FALLIDO, guardado.getEstadoEnvio());
        assertEquals(3, guardado.getIntentos());
        assertNull(guardado.getEnviadoEn());
    }

    @Test
    @DisplayName("Falla en el primer intento pero luego funciona → recupera y guarda EXITOSO con 2 intentos")
    void enviarVoucherGrupal_fallaPrimerIntento_luegoExitoso() {
        Atencion titular = Atencion.builder()
                .id(1L).numeroCorrelativo("SGC-000000001").build();
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000001"))
                .thenReturn(Optional.of(titular));
        doThrow(new org.springframework.mail.MailSendException("Timeout"))
                .doNothing()
                .when(mailSender).send(any(MimeMessage.class));

        emailService.enviarVoucherGrupal(
                "grupo@test.com",null,
                "SGC-000000001 (+1)",
                PDF_BYTES,
                List.of("Juan Perez", "Flor Vasquez"),IdiomaVoucherEnum.ES);

        verify(mailSender, times(2)).send(any(MimeMessage.class));
        ArgumentCaptor<EnvioPdf> captor = ArgumentCaptor.forClass(EnvioPdf.class);
        verify(envioPdfRepository).save(captor.capture());
        assertEquals(EstadoEnvioEnum.EXITOSO, captor.getValue().getEstadoEnvio());
        assertEquals(2, captor.getValue().getIntentos());
    }

    @Test
    @DisplayName("Si no se encuentra la atención titular por correlativo, no lanza excepción — solo guarda con atencion=null")
    void enviarVoucherGrupal_atencionNoEncontrada_noLanzaExcepcion() {
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000001"))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> emailService.enviarVoucherGrupal(
                "grupo@test.com",null,
                "SGC-000000001 (+1)",
                PDF_BYTES,
                List.of("Juan Perez", "Flor Vasquez"),IdiomaVoucherEnum.ES));

        ArgumentCaptor<EnvioPdf> captor = ArgumentCaptor.forClass(EnvioPdf.class);
        verify(envioPdfRepository).save(captor.capture());
        assertNull(captor.getValue().getAtencion());
    }

    @Test
    @DisplayName("Lista de nombres vacía → no lanza excepción (caso borde defensivo)")
    void enviarVoucherGrupal_listaNombresVacia_noLanzaExcepcion() {
        Atencion titular = Atencion.builder()
                .id(1L).numeroCorrelativo("SGC-000000001").build();
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000001"))
                .thenReturn(Optional.of(titular));

        assertDoesNotThrow(() -> emailService.enviarVoucherGrupal(
                "grupo@test.com", null,"SGC-000000001", PDF_BYTES, List.of(),IdiomaVoucherEnum.ES));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Diferenciación de mensaje: primer envío ("asignados") vs. reenvío por
    // actualización ("actualizados") — individual y grupal.
    // ══════════════════════════════════════════════════════════════════════════

    /** Extrae el cuerpo HTML de un MimeMessage armado con MimeMessageHelper. */
    private String extraerHtml(MimeMessage msg) throws Exception {
        Object content = msg.getContent();
        if (content instanceof Multipart mp) {
            Object partContent = mp.getBodyPart(0).getContent();
            // MimeMessageHelper(msg, true, encoding) usa MULTIPART_MODE_MIXED_RELATED:
            // el bodyPart(0) del multipart/mixed es en realidad OTRO multipart (related)
            // que contiene el text/html real como su propio bodyPart(0).
            if (partContent instanceof Multipart relatedMp) {
                return relatedMp.getBodyPart(0).getContent().toString();
            }
            return partContent.toString();
        }
        return content.toString();
    }

    @Test
    @DisplayName("enviarVoucher (primer envío, individual): el correo dice \"asignados\"")
    void enviarVoucher_primerEnvio_textoIndicaAsignacion() throws Exception {
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000001")).thenReturn(Optional.empty());

        emailService.enviarVoucher("juan@test.com", null, "SGC-000000001", PDF_BYTES, "Juan Perez",IdiomaVoucherEnum.ES);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        String cuerpo = extraerHtml(captor.getValue());
        assertTrue(cuerpo.contains("Adjunto encontrará su comprobante de servicios asignados."));
        assertFalse(cuerpo.contains("servicios actualizados"));
    }

    @Test
    @DisplayName("enviarVoucherActualizado (reenvío por actualización, individual vía "
            + "regenerar-pdf): el correo dice \"actualizados\", no repite el texto del primer envío")
    void enviarVoucherActualizado_textoIndicaActualizacion() throws Exception {
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000001")).thenReturn(Optional.empty());

        emailService.enviarVoucherActualizado("juan@test.com", null, "SGC-000000001", PDF_BYTES, "Juan Perez",IdiomaVoucherEnum.ES);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        String cuerpo = extraerHtml(captor.getValue());
        assertTrue(cuerpo.contains("Adjunto encontrará su comprobante de servicios actualizados."));
        assertFalse(cuerpo.contains("servicios asignados"));
    }

    @Test
    @DisplayName("enviarVoucherGrupal (primer envío grupal): el correo dice \"asignados\"")
    void enviarVoucherGrupal_primerEnvio_textoIndicaAsignacion() throws Exception {
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000001")).thenReturn(Optional.empty());

        emailService.enviarVoucherGrupal("grupo@test.com", null, "SGC-000000001 (+1)",
                PDF_BYTES, List.of("Juan Perez", "Flor Vasquez"),IdiomaVoucherEnum.ES);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        String cuerpo = extraerHtml(captor.getValue());
        assertTrue(cuerpo.contains("comprobante de servicios asignados para los siguientes pasajeros"));
        assertFalse(cuerpo.contains("servicios actualizados"));
    }

    @Test
    @DisplayName("reenviarVoucherGrupal (reenvío grupal por actualización): el correo dice "
            + "\"actualizados\", no repite el texto del primer envío grupal")
    void reenviarVoucherGrupal_textoIndicaActualizacion() throws Exception {
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000001")).thenReturn(Optional.empty());

        emailService.reenviarVoucherGrupal("grupo@test.com", null, "SGC-000000001 (+1)",
                PDF_BYTES, List.of("Juan Perez", "Flor Vasquez"), IdiomaVoucherEnum.ES);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        String cuerpo = extraerHtml(captor.getValue());
        assertTrue(cuerpo.contains("comprobante de servicios actualizados para los siguientes pasajeros"));
        assertFalse(cuerpo.contains("servicios asignados"));
    }
}
