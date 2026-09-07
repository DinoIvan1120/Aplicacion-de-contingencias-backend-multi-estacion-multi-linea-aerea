package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.enumeration.EstadoEnvioEnum;
import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.model.EnvioPdf;
import com.saasa.contingencias.domain.model.Usuario;
import com.saasa.contingencias.domain.repository.AtencionRepository;
import com.saasa.contingencias.domain.repository.EnvioPdfRepository;
import com.saasa.contingencias.domain.repository.UsuarioRepository;
import com.saasa.contingencias.service.IAuditoriaService;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.type.PhoneNumber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwilioWhatsAppServiceImplTest {

    private static final String PUBLIC_BASE_URL = "https://vouchers.saasa.pe";
    private static final String FROM_NUMBER = "whatsapp:+14155238886";

    @Mock AtencionRepository atencionRepository;
    @Mock EnvioPdfRepository envioPdfRepository;
    @Mock UsuarioRepository usuarioRepository;
    @Mock IAuditoriaService auditoriaService;

    @InjectMocks TwilioWhatsAppServiceImpl whatsAppService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(whatsAppService, "publicBaseUrl", PUBLIC_BASE_URL);
        ReflectionTestUtils.setField(whatsAppService, "fromNumber", FROM_NUMBER);
    }

    private Atencion atencionMock() {
        return Atencion.builder()
                .id(1L)
                .numeroCorrelativo("SGC-000000236")
                .nombre("Cecilia")
                .apellido("Castillo Aguirre")
                .pdfUrl("prd/vouchers/2026/SGC-000000236.pdf")
                .build();
    }

    @Test
    @DisplayName("enviarVoucherWhatsApp: el mensaje usa el enlace corto /v/{correlativo}, no la URL de S3")
    void enviarVoucherWhatsApp_usaEnlaceCorto_noUrlS3() {
        String correlativo = "SGC-000000236";
        String urlS3CruDaQueNoDeberiaAparecer =
                "https://integrityflow-apps-storage.s3.us-east-2.amazonaws.com/dev/vouchers/2026/"
                        + correlativo + ".pdf?X-Amz-Signature=abc123";

        when(atencionRepository.findByNumeroCorrelativo(correlativo)).thenReturn(Optional.empty());

        try (MockedStatic<Message> messageStatic = mockStatic(Message.class)) {
            MessageCreator creatorMock = mock(MessageCreator.class);
            messageStatic.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), anyString()))
                    .thenReturn(creatorMock);
            when(creatorMock.create()).thenReturn(mock(Message.class));

            whatsAppService.enviarVoucherWhatsApp(
                    "+51987654321", correlativo, "Cecilia Castillo Aguirre", urlS3CruDaQueNoDeberiaAparecer,IdiomaVoucherEnum.ES);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            messageStatic.verify(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), bodyCaptor.capture()));

            String cuerpoEnviado = bodyCaptor.getValue();
            assertTrue(cuerpoEnviado.contains(PUBLIC_BASE_URL + "/v/" + correlativo),
                    "El mensaje debe contener el enlace corto propio");
            assertFalse(cuerpoEnviado.contains("amazonaws.com"),
                    "El mensaje NO debe exponer el dominio real de S3");
            assertFalse(cuerpoEnviado.contains("X-Amz-Signature"),
                    "El mensaje NO debe exponer la firma de AWS");
        }

        verify(envioPdfRepository).save(any(EnvioPdf.class));
    }

    @Test
    @DisplayName("enviarVoucherWhatsApp (primer envío): el mensaje dice \"asignados\", no \"actualizados\"")
    void enviarVoucherWhatsApp_mensajeIndicaAsignacionInicial() {
        String correlativo = "SGC-000000236";
        when(atencionRepository.findByNumeroCorrelativo(correlativo)).thenReturn(Optional.empty());

        try (MockedStatic<Message> messageStatic = mockStatic(Message.class)) {
            MessageCreator creatorMock = mock(MessageCreator.class);
            messageStatic.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), anyString()))
                    .thenReturn(creatorMock);
            when(creatorMock.create()).thenReturn(mock(Message.class));

            whatsAppService.enviarVoucherWhatsApp("+51987654321", correlativo, "Cecilia", "cualquier-url",IdiomaVoucherEnum.ES);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            messageStatic.verify(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), bodyCaptor.capture()));

            String cuerpoEnviado = bodyCaptor.getValue();
            assertTrue(cuerpoEnviado.contains("han sido asignados correctamente"),
                    "El primer envío debe indicar que los servicios fueron ASIGNADOS");
            assertFalse(cuerpoEnviado.contains("han sido actualizados correctamente"),
                    "El primer envío NO debe usar el texto de actualización");
        }
    }

    @Test
    @DisplayName("enviarVoucherWhatsApp: sin teléfono registrado, no llama a Twilio")
    void enviarVoucherWhatsApp_sinTelefono_noEnvia() {
        try (MockedStatic<Message> messageStatic = mockStatic(Message.class)) {
            whatsAppService.enviarVoucherWhatsApp(null, "SGC-000000236", "Cecilia", "cualquier-url",IdiomaVoucherEnum.ES);

            messageStatic.verifyNoInteractions();
        }
        verifyNoInteractions(envioPdfRepository);
    }

    @Test
    @DisplayName("reenviarVoucherWhatsApp: el mensaje usa el enlace corto y ya no depende de S3StorageService")
    void reenviarVoucherWhatsApp_usaEnlaceCorto() {
        Atencion atencion = atencionMock();
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencion));
        when(usuarioRepository.findById(5L)).thenReturn(Optional.of(new Usuario()));

        try (MockedStatic<Message> messageStatic = mockStatic(Message.class)) {
            MessageCreator creatorMock = mock(MessageCreator.class);
            messageStatic.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), anyString()))
                    .thenReturn(creatorMock);
            when(creatorMock.create()).thenReturn(mock(Message.class));

            whatsAppService.reenviarVoucherWhatsApp(1L, "+51987654321", 5L,null);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            messageStatic.verify(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), bodyCaptor.capture()));

            String cuerpoEnviado = bodyCaptor.getValue();
            assertTrue(cuerpoEnviado.contains(PUBLIC_BASE_URL + "/v/SGC-000000236"));
            assertFalse(cuerpoEnviado.contains("amazonaws.com"));
        }

        ArgumentCaptor<EnvioPdf> envioCaptor = ArgumentCaptor.forClass(EnvioPdf.class);
        verify(envioPdfRepository).save(envioCaptor.capture());
        assertEquals(EstadoEnvioEnum.EXITOSO, envioCaptor.getValue().getEstadoEnvio());

        verify(auditoriaService).registrar(eq(5L), eq("REENVIAR_WHATSAPP"), eq("ATENCIONES"), isNull(), any());
    }

    @Test
    @DisplayName("reenviarVoucherWhatsApp (reenvío por actualización): el mensaje dice "
            + "\"actualizados\", no repite el texto de la asignación inicial")
    void reenviarVoucherWhatsApp_mensajeIndicaActualizacion() {
        Atencion atencion = atencionMock();
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencion));
        when(usuarioRepository.findById(5L)).thenReturn(Optional.of(new Usuario()));

        try (MockedStatic<Message> messageStatic = mockStatic(Message.class)) {
            MessageCreator creatorMock = mock(MessageCreator.class);
            messageStatic.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), anyString()))
                    .thenReturn(creatorMock);
            when(creatorMock.create()).thenReturn(mock(Message.class));

            whatsAppService.reenviarVoucherWhatsApp(1L, "+51987654321", 5L,null);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            messageStatic.verify(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), bodyCaptor.capture()));

            String cuerpoEnviado = bodyCaptor.getValue();
            assertTrue(cuerpoEnviado.contains("han sido actualizados correctamente"),
                    "El reenvío por actualización debe indicar que los servicios fueron ACTUALIZADOS");
            assertFalse(cuerpoEnviado.contains("han sido asignados correctamente"),
                    "El reenvío por actualización NO debe repetir el texto del primer envío");
        }
    }

    @Test
    @DisplayName("enviarVoucherWhatsApp (idioma EN): el mensaje sale completo en inglés — "
            + "encabezado, saludo, estado, etiquetas y pie de página")
    void enviarVoucherWhatsApp_idiomaIngles_mensajeCompletoEnIngles() {
        String correlativo = "SGC-000000236";
        when(atencionRepository.findByNumeroCorrelativo(correlativo)).thenReturn(Optional.empty());

        try (MockedStatic<Message> messageStatic = mockStatic(Message.class)) {
            MessageCreator creatorMock = mock(MessageCreator.class);
            messageStatic.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), anyString()))
                    .thenReturn(creatorMock);
            when(creatorMock.create()).thenReturn(mock(Message.class));

            whatsAppService.enviarVoucherWhatsApp(
                    "+51987654321", correlativo, "Cecilia", "cualquier-url", IdiomaVoucherEnum.EN);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            messageStatic.verify(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), bodyCaptor.capture()));

            String cuerpoEnviado = bodyCaptor.getValue();
            assertTrue(cuerpoEnviado.contains("Contingency Services"));
            assertTrue(cuerpoEnviado.contains("Dear Cecilia,"));
            assertTrue(cuerpoEnviado.contains("Service code:"));
            assertTrue(cuerpoEnviado.contains("Download your voucher here:"));
            assertFalse(cuerpoEnviado.contains("Estimado/a"),
                    "No debe mezclar texto en español cuando el idioma es EN");
        }
    }

    @Test
    @DisplayName("reenviarVoucherWhatsApp (idioma EN persistido en la atención): el mensaje "
            + "de reenvío también sale en inglés")
    void reenviarVoucherWhatsApp_idiomaIngles_mensajeEnIngles() {
        Atencion atencion = atencionMock();
        atencion.setIdiomaVoucher(IdiomaVoucherEnum.EN);
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencion));
        when(usuarioRepository.findById(5L)).thenReturn(Optional.of(new Usuario()));

        try (MockedStatic<Message> messageStatic = mockStatic(Message.class)) {
            MessageCreator creatorMock = mock(MessageCreator.class);
            messageStatic.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), anyString()))
                    .thenReturn(creatorMock);
            when(creatorMock.create()).thenReturn(mock(Message.class));

            whatsAppService.reenviarVoucherWhatsApp(1L, "+51987654321", 5L,null);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            messageStatic.verify(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), bodyCaptor.capture()));

            String cuerpoEnviado = bodyCaptor.getValue();
            assertTrue(cuerpoEnviado.contains("have been updated successfully"));
            assertFalse(cuerpoEnviado.contains("actualizados correctamente"));
        }
    }

    @Test
    @DisplayName("reenviarVoucherWhatsApp: sin idioma persistido en la atención, asume Español (comportamiento previo)")
    void reenviarVoucherWhatsApp_sinIdiomaPersistido_asumeEspanol() {
        Atencion atencion = atencionMock(); // idiomaVoucher = null
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencion));
        when(usuarioRepository.findById(5L)).thenReturn(Optional.of(new Usuario()));

        try (MockedStatic<Message> messageStatic = mockStatic(Message.class)) {
            MessageCreator creatorMock = mock(MessageCreator.class);
            messageStatic.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), anyString()))
                    .thenReturn(creatorMock);
            when(creatorMock.create()).thenReturn(mock(Message.class));

            whatsAppService.reenviarVoucherWhatsApp(1L, "+51987654321", 5L,null);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            messageStatic.verify(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), bodyCaptor.capture()));

            assertTrue(bodyCaptor.getValue().contains("actualizados correctamente"));
        }
    }

    @Test
    @DisplayName("reenviarVoucherWhatsApp: atención inexistente lanza RecursoNoEncontradoException")
    void reenviarVoucherWhatsApp_atencionInexistente_lanzaExcepcion() {
        when(atencionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class,
                () -> whatsAppService.reenviarVoucherWhatsApp(99L, "+51987654321", 5L,null));

        verifyNoInteractions(envioPdfRepository);
    }

    @Test
    @DisplayName("reenviarVoucherWhatsApp: el idioma pasado explícitamente prevalece sobre el "
            + "persistido en la atención (regresión: condición de carrera @Async/@Transactional "
            + "que hacía que el mensaje quedara en el idioma del envío anterior)")
    void reenviarVoucherWhatsApp_idiomaExplicito_prevaleceSobrePersistido() {
        // Simula el escenario reportado: la atención en BD todavía tiene el
        // idioma del envío anterior (EN) porque el llamador (p. ej.
        // regenerarYEnviarPdf) aún no hizo commit de la actualización a ES,
        // pero pasa el nuevo idioma explícitamente como parámetro.
        Atencion atencion = atencionMock();
        atencion.setIdiomaVoucher(IdiomaVoucherEnum.EN);
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencion));
        when(usuarioRepository.findById(5L)).thenReturn(Optional.of(new Usuario()));

        try (MockedStatic<Message> messageStatic = mockStatic(Message.class)) {
            MessageCreator creatorMock = mock(MessageCreator.class);
            messageStatic.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), anyString()))
                    .thenReturn(creatorMock);
            when(creatorMock.create()).thenReturn(mock(Message.class));

            whatsAppService.reenviarVoucherWhatsApp(1L, "+51987654321", 5L, IdiomaVoucherEnum.ES);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            messageStatic.verify(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), bodyCaptor.capture()));

            String cuerpoEnviado = bodyCaptor.getValue();
            assertTrue(cuerpoEnviado.contains("actualizados correctamente"));
            assertFalse(cuerpoEnviado.contains("have been updated successfully"));
        }
    }
}