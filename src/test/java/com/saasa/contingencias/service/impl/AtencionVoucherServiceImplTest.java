package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.exception.PdfGenerationException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.dto.request.GenerarVoucherRequest;
import com.saasa.contingencias.domain.dto.request.VoucherGrupalRequest;
import com.saasa.contingencias.domain.dto.response.VoucherGrupalResponse;
import com.saasa.contingencias.domain.dto.response.VoucherResponse;
import com.saasa.contingencias.domain.enumeration.EstadoAtencionEnum;
import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;
import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.model.RegistroVueloDiario;
import com.saasa.contingencias.domain.model.ServicioAsignado;
import com.saasa.contingencias.domain.model.Vuelo;
import com.saasa.contingencias.domain.repository.AtencionRepository;
import com.saasa.contingencias.domain.repository.ServicioAsignadoRepository;
import com.saasa.contingencias.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AtencionVoucherServiceImplTest {

    @Mock AtencionRepository atencionRepository;
    @Mock ServicioAsignadoRepository servicioAsignadoRepository;
    @Mock IPdfGeneratorService pdfGeneratorService;
    @Mock IS3StorageService s3StorageService;
    @Mock IEmailService emailService;
    @Mock IWhatsAppService whatsAppService;
    @Mock IDisponibilidadService disponibilidadService;
    @Mock IAuditoriaService auditoriaService;

    @InjectMocks AtencionVoucherServiceImpl voucherService;

    private Atencion atencionBase;
    private static final byte[] PDF_BYTES = new byte[]{1, 2, 3};
    private static final String PDF_URL = "https://s3.test/voucher.pdf";

    @BeforeEach
    void setUp() {
        atencionBase = Atencion.builder()
                .id(1L)
                .numeroCorrelativo("SGC-000000001")
                .nombre("Juan")
                .apellido("Perez")
                .correo("juan@test.com")
                .telefono("+51999000111")
                .montoTotal(BigDecimal.TEN)
                .estado(EstadoAtencionEnum.ACTIVO)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // generarVoucherPdf()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void generarVoucherPdf_atencionExistente_subeA_S3_YActualizaUrl() {
        // Arrange
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencionBase));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucher(any(), any())).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);
        when(atencionRepository.save(any())).thenReturn(atencionBase);

        // Act
        VoucherResponse response = voucherService.generarVoucherPdf(1L);

        // Assert
        assertNotNull(response);
        assertEquals("SGC-000000001", response.numeroVoucher());
        assertEquals(PDF_URL, response.pdfUrl());
        assertFalse(response.emailEnviado());
        verify(s3StorageService).subirPdf(PDF_BYTES, "SGC-000000001.pdf");
        verify(atencionRepository).save(atencionBase);
        assertEquals(PDF_URL, atencionBase.getPdfUrl());
    }

    @Test
    void generarVoucherPdf_atencionNoExistente_lanzaRecursoNoEncontrado() {
        // Arrange
        when(atencionRepository.findById(99L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(RecursoNoEncontradoException.class,
                () -> voucherService.generarVoucherPdf(99L));
        verify(pdfGeneratorService, never()).generarVoucher(any(), any());
    }

    @Test
    void generarVoucherPdf_fallaPdfGenerator_lanzaPdfGenerationException() {
        // Arrange
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencionBase));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucher(any(), any()))
                .thenThrow(new RuntimeException("Template error"));

        // Act & Assert
        assertThrows(PdfGenerationException.class,
                () -> voucherService.generarVoucherPdf(1L));
        verify(s3StorageService, never()).subirPdf(any(), any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // generarYEnviarVoucher()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void generarYEnviarVoucher_conTelefono_enviaEmailYWhatsApp() {
        // Arrange
        GenerarVoucherRequest request = new GenerarVoucherRequest("destino@test.com",null,null,null,null,null);
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencionBase));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucher(any(), any())).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);
        when(s3StorageService.generarUrlConExpiracion(PDF_URL, 7))
                .thenReturn(PDF_URL + "?expiry=7d");
        when(atencionRepository.save(any())).thenReturn(atencionBase);

        // Act
        VoucherResponse response = voucherService.generarYEnviarVoucher(1L, request, 1L);

        // Assert
        assertTrue(response.emailEnviado());
        assertEquals("destino@test.com", response.correoDestino());
        verify(emailService).enviarVoucher(
                eq("destino@test.com"),
                eq((List<String>) null),
                eq("SGC-000000001"),
                eq(PDF_BYTES),
                eq("Juan Perez"), eq(IdiomaVoucherEnum.ES));
        verify(whatsAppService).enviarVoucherWhatsApp(
                eq("+51999000111"),
                eq("SGC-000000001"),
                eq("Juan Perez"),
                anyString(),eq(IdiomaVoucherEnum.ES));
    }

    @Test
    void generarYEnviarVoucher_sinTelefono_soloEnviaEmail() {
        // Arrange — atención sin teléfono
        atencionBase = Atencion.builder()
                .id(1L)
                .numeroCorrelativo("SGC-000000001")
                .nombre("Juan")
                .apellido("Perez")
                .correo("juan@test.com")
                .telefono(null)           // ← sin teléfono
                .montoTotal(BigDecimal.TEN)
                .estado(EstadoAtencionEnum.ACTIVO)
                .build();

        GenerarVoucherRequest request = new GenerarVoucherRequest(null,null,null,null,null,null); // usa correo de la atención
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencionBase));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucher(any(), any())).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);
        when(atencionRepository.save(any())).thenReturn(atencionBase);

        // Act
        VoucherResponse response = voucherService.generarYEnviarVoucher(1L, request, 1L);

        // Assert
        assertTrue(response.emailEnviado());
        verify(emailService).enviarVoucher(
                eq("juan@test.com"),
                eq((List<String>) null),
                eq("SGC-000000001"),
                eq(PDF_BYTES),
                eq("Juan Perez"),eq(IdiomaVoucherEnum.ES));
        verify(whatsAppService, never()).enviarVoucherWhatsApp(any(), any(), any(), any(),any());
    }

    @Test
    void generarYEnviarVoucher_sinCorreoDestino_usaCorreoDeLaAtencion() {
        // Arrange
        GenerarVoucherRequest request = new GenerarVoucherRequest(null,null,null,null,null,null);
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencionBase));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucher(any(), any())).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);
        when(s3StorageService.generarUrlConExpiracion(any(), anyInt()))
                .thenReturn(PDF_URL + "?expiry=7d");
        when(atencionRepository.save(any())).thenReturn(atencionBase);

        // Act
        VoucherResponse response = voucherService.generarYEnviarVoucher(1L, request, 1L);

        // Assert
        assertEquals("juan@test.com", response.correoDestino());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // descargarPdf()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void descargarPdf_atencionConUrl_retornaBytesDelPdf() {
        // Arrange
        atencionBase.setPdfUrl(PDF_URL);
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencionBase));
        when(s3StorageService.descargarPdf(PDF_URL)).thenReturn(PDF_BYTES);

        // Act
        byte[] result = voucherService.descargarPdf(1L);

        // Assert
        assertArrayEquals(PDF_BYTES, result);
        verify(s3StorageService).descargarPdf(PDF_URL);
    }

    @Test
    void descargarPdf_atencionNoExistente_lanzaRecursoNoEncontrado() {
        // Arrange
        when(atencionRepository.findById(99L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(RecursoNoEncontradoException.class,
                () -> voucherService.descargarPdf(99L));
        verify(s3StorageService, never()).descargarPdf(any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // urlFirmadaVoucher()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void urlFirmadaVoucher_atencionExistente_retornaMapConUrl() {
        // Arrange
        atencionBase.setPdfUrl(PDF_URL);
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencionBase));
        when(s3StorageService.generarUrlFirmada(PDF_URL))
                .thenReturn(PDF_URL + "?signed=true");

        // Act
        var result = voucherService.urlFirmadaVoucher(1L);

        // Assert
        assertEquals("true", result.get("success"));
        assertEquals(PDF_URL + "?signed=true", result.get("downloadUrl"));
        assertEquals("15 minutos", result.get("expiresIn"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // obtenerServiciosDe() — fallback por grupoId (fix del doble-decremento)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void generarVoucherPdf_atencionConServiciosPropios_noConsultaPorGrupo() {
        // Arrange — atención SIN grupoId, con servicios propios
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencionBase));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucher(any(), any())).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);
        when(atencionRepository.save(any())).thenReturn(atencionBase);

        voucherService.generarVoucherPdf(1L);

        verify(servicioAsignadoRepository, never()).findByAtencionGrupoId(any());
    }

    @Test
    void generarVoucherPdf_atencionDeGrupoSinServiciosPropios_usaServiciosDelGrupo() {
        // Arrange — la atención NO es la titular (no tiene ServicioAsignado
        // propios), pero pertenece a un grupoId cuya titular sí los tiene.
        atencionBase.setGrupoId("GRUPO-XYZ");
        ServicioAsignado servicioDelGrupo = ServicioAsignado.builder()
                .id(1L).atencion(atencionBase)
                .tipoDetalle(TipoDetalleEnum.HOTEL)
                .cantidad(1)
                .build();

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencionBase));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of()); // vacío → cae al fallback
        when(servicioAsignadoRepository.findByAtencionGrupoId("GRUPO-XYZ"))
                .thenReturn(List.of(servicioDelGrupo));
        when(pdfGeneratorService.generarVoucher(any(), any())).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);
        when(atencionRepository.save(any())).thenReturn(atencionBase);

        voucherService.generarVoucherPdf(1L);

        verify(pdfGeneratorService).generarVoucher(atencionBase, List.of(servicioDelGrupo));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // generarVoucherGrupalPdf() / generarYEnviarVoucherGrupal()
    // ══════════════════════════════════════════════════════════════════════════

    private Atencion pasajeroGrupo(Long id, String correlativo, String nombre, String apellido,
                                   String pnr, Long vueloId, String telefono) {
        Vuelo vuelo = Vuelo.builder().id(vueloId).codigoVuelo("PU301").build();
        return Atencion.builder()
                .id(id)
                .numeroCorrelativo(correlativo)
                .nombre(nombre)
                .apellido(apellido)
                .pnr(pnr)
                .correo(nombre.toLowerCase() + "@test.com")
                .telefono(telefono)
                .vuelo(vuelo)
                .montoTotal(BigDecimal.ZERO)
                .estado(EstadoAtencionEnum.ACTIVO)
                .build();
    }

    @Test
    void generarVoucherGrupalPdf_serviciosCompartidos_generaUnSoloPdfYPropagaMontoATodos() {
        // Arrange — 2 pasajeros, mismo PNR/vuelo. Solo el titular (id=1) tiene
        // montoTotal real (simula que solo a él se le asignaron los servicios,
        // como hace el frontend en modo compartido). Cada uno ya trae su propio
        // codigoAutorizacion único, generado al asignarle servicios.
        Atencion titular = pasajeroGrupo(1L, "SGC-000000001", "Juan", "Perez", "ABC123", 10L, null);
        titular.setMontoTotal(new BigDecimal("300.00"));
        titular.setCodigoAutorizacion("SGC-000000001-999");
        Atencion acompanante = pasajeroGrupo(2L, "SGC-000000002", "Flor", "Vasquez", "ABC123", 10L, null);
        acompanante.setCodigoAutorizacion("SGC-000000002-888");

        VoucherGrupalRequest request = new VoucherGrupalRequest(List.of(1L, 2L), null, true,null,null,null,null,null);

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(titular));
        when(atencionRepository.findById(2L)).thenReturn(Optional.of(acompanante));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucherGrupal(any(), any(), eq(true))).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);

        // Act
        VoucherGrupalResponse response = voucherService.generarVoucherGrupalPdf(request);

        // Assert
        assertEquals(List.of("SGC-000000001", "SGC-000000002"), response.numerosVoucher());
        assertEquals(PDF_URL, response.pdfUrl());
        assertFalse(response.emailEnviado());

        // El acompañante debe recibir el mismo pdfUrl y monto que el titular...
        assertEquals(PDF_URL, acompanante.getPdfUrl());
        assertEquals(new BigDecimal("300.00"), acompanante.getMontoTotal());
        // ...pero su codigoAutorizacion NO se toca: cada atención conserva el
        // propio (columna UNIQUE en BD — ver test de regresión más abajo).
        assertEquals("SGC-000000002-888", acompanante.getCodigoAutorizacion());
        assertEquals("SGC-000000001-999", titular.getCodigoAutorizacion());

        verify(s3StorageService).subirPdf(PDF_BYTES, "GRUPO-SGC-000000001.pdf");
        verify(atencionRepository).saveAll(List.of(titular, acompanante));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Regresión — bug: 500 "Error interno del servidor" al generar voucher
    // grupal con 2+ pasajeros y servicios compartidos.
    //
    // Causa: propagarMontoYCodigoDelTitular() copiaba el codigoAutorizacion
    // del titular a TODOS los integrantes del grupo. Como la columna tiene
    // @Column(unique = true) en BD, guardar 2+ filas con el mismo valor
    // disparaba DataIntegrityViolationException en saveAll(), que caía en
    // el handler genérico de Exception → 500.
    //
    // Fix: cada Atencion conserva su propio codigoAutorizacion (ya único,
    // generado al asignarle servicios en AtencionServiceImpl). Solo el
    // montoTotal se propaga entre los integrantes del grupo.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void generarVoucherGrupalPdf_serviciosCompartidos_conTresPasajeros_noDuplicaCodigoAutorizacion() {
        // Arrange — 3 pasajeros con servicios compartidos, cada uno con su
        // propio codigoAutorizacion único (simula el estado real en BD).
        Atencion titular = pasajeroGrupo(1L, "SGC-000000001", "Juan", "Perez", "ABC123", 10L, null);
        titular.setMontoTotal(new BigDecimal("450.00"));
        titular.setCodigoAutorizacion("SGC-000000001-100");

        Atencion pasajero2 = pasajeroGrupo(2L, "SGC-000000002", "Flor", "Vasquez", "ABC123", 10L, null);
        pasajero2.setCodigoAutorizacion("SGC-000000002-200");

        Atencion pasajero3 = pasajeroGrupo(3L, "SGC-000000003", "Ana", "Ramos", "ABC123", 10L, null);
        pasajero3.setCodigoAutorizacion("SGC-000000003-300");

        VoucherGrupalRequest request = new VoucherGrupalRequest(List.of(1L, 2L, 3L), null, true,null,null,null,null,null);

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(titular));
        when(atencionRepository.findById(2L)).thenReturn(Optional.of(pasajero2));
        when(atencionRepository.findById(3L)).thenReturn(Optional.of(pasajero3));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucherGrupal(any(), any(), eq(true))).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);

        // Act — no debe lanzar ninguna excepción
        assertDoesNotThrow(() -> voucherService.generarVoucherGrupalPdf(request));

        // Assert — cada atención mantiene su propio codigoAutorizacion:
        // si el fix se rompiera (volviera a propagar el del titular),
        // los 3 valores serían iguales y este assert lo detectaría.
        List<String> codigos = List.of(
                titular.getCodigoAutorizacion(),
                pasajero2.getCodigoAutorizacion(),
                pasajero3.getCodigoAutorizacion());
        assertEquals(3, codigos.stream().distinct().count(),
                "Los codigoAutorizacion deben seguir siendo únicos entre los integrantes del grupo");
        assertEquals("SGC-000000001-100", titular.getCodigoAutorizacion());
        assertEquals("SGC-000000002-200", pasajero2.getCodigoAutorizacion());
        assertEquals("SGC-000000003-300", pasajero3.getCodigoAutorizacion());

        // El monto sí se propaga a todo el grupo
        assertEquals(new BigDecimal("450.00"), pasajero2.getMontoTotal());
        assertEquals(new BigDecimal("450.00"), pasajero3.getMontoTotal());

        verify(atencionRepository).saveAll(List.of(titular, pasajero2, pasajero3));
    }
    @Test
    void generarVoucherGrupalPdf_pnrDistinto_lanzaBadRequestException() {
        Atencion p1 = pasajeroGrupo(1L, "SGC-000000001", "Juan", "Perez", "ABC123", 10L, null);
        Atencion p2 = pasajeroGrupo(2L, "SGC-000000002", "Flor", "Vasquez", "DIFERENTE", 10L, null);

        VoucherGrupalRequest request = new VoucherGrupalRequest(List.of(1L, 2L), null, true,null,null,null,null,null);

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(p1));
        when(atencionRepository.findById(2L)).thenReturn(Optional.of(p2));

        assertThrows(BadRequestException.class,
                () -> voucherService.generarVoucherGrupalPdf(request));
        verify(pdfGeneratorService, never()).generarVoucherGrupal(any(), any(), anyBoolean());
    }

    @Test
    void generarVoucherGrupalPdf_vueloDistinto_lanzaBadRequestException() {
        Atencion p1 = pasajeroGrupo(1L, "SGC-000000001", "Juan", "Perez", "ABC123", 10L, null);
        Atencion p2 = pasajeroGrupo(2L, "SGC-000000002", "Flor", "Vasquez", "ABC123", 99L, null); // ← otro vuelo

        VoucherGrupalRequest request = new VoucherGrupalRequest(List.of(1L, 2L), null, true,null,null,null,null,null);

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(p1));
        when(atencionRepository.findById(2L)).thenReturn(Optional.of(p2));

        assertThrows(BadRequestException.class,
                () -> voucherService.generarVoucherGrupalPdf(request));
    }

    @Test
    void generarYEnviarVoucherGrupal_enviaUnSoloCorreoConTodosLosNombres() {
        Atencion titular = pasajeroGrupo(1L, "SGC-000000001", "Juan", "Perez", "ABC123", 10L, null);
        Atencion acompanante = pasajeroGrupo(2L, "SGC-000000002", "Flor", "Vasquez", "ABC123", 10L, null);

        VoucherGrupalRequest request = new VoucherGrupalRequest(List.of(1L, 2L), "grupo@test.com", true,null,null,null,null,null);

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(titular));
        when(atencionRepository.findById(2L)).thenReturn(Optional.of(acompanante));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucherGrupal(any(), any(), eq(true))).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);

        VoucherGrupalResponse response = voucherService.generarYEnviarVoucherGrupal(request, 1L);

        assertTrue(response.emailEnviado());
        assertEquals("grupo@test.com", response.correoDestino());
        verify(emailService).enviarVoucherGrupal(
                eq("grupo@test.com"),
                eq((List<String>) null),
                eq("SGC-000000001 (+1)"),
                eq(PDF_BYTES),
                eq(List.of("Juan Perez", "Flor Vasquez")),eq(IdiomaVoucherEnum.ES));
        verify(whatsAppService, never()).enviarVoucherWhatsApp(any(), any(), any(), any(),any());
    }

    @Test
    void generarYEnviarVoucherGrupal_conTelefonos_enviaWhatsAppACadaPasajeroConTelefono() {
        Atencion titular = pasajeroGrupo(1L, "SGC-000000001", "Juan", "Perez", "ABC123", 10L, "+51999000111");
        Atencion acompanante = pasajeroGrupo(2L, "SGC-000000002", "Flor", "Vasquez", "ABC123", 10L, null); // sin tel.

        VoucherGrupalRequest request = new VoucherGrupalRequest(List.of(1L, 2L), "grupo@test.com", true,null,null,null,null,null);

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(titular));
        when(atencionRepository.findById(2L)).thenReturn(Optional.of(acompanante));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucherGrupal(any(), any(), eq(true))).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);
        when(s3StorageService.generarUrlConExpiracion(PDF_URL, 7)).thenReturn(PDF_URL + "?exp=7d");

        voucherService.generarYEnviarVoucherGrupal(request, 1L);

        // Solo el titular tiene teléfono → solo se envía 1 WhatsApp
        verify(whatsAppService, times(1)).enviarVoucherWhatsApp(
                eq("+51999000111"), eq("SGC-000000001"), eq("Juan Perez"), anyString(),eq(IdiomaVoucherEnum.ES));
        verify(whatsAppService, never()).enviarVoucherWhatsApp(
                eq(null), any(), any(), any(),any());
    }

    @Test
    @DisplayName("Carga masiva: 3 pasajeros del mismo grupo heredan el teléfono del titular "
            + "(mismo número) → el titular recibe UN solo WhatsApp, no uno por integrante")
    void generarYEnviarVoucherGrupal_variosConMismoTelefono_enviaUnSoloWhatsApp() {
        // Simula el comportamiento real de la carga masiva por Excel: cuando
        // un integrante no trae celular propio, hereda el del titular — las
        // 3 Atencion del grupo terminan con el MISMO número de teléfono.
        String telefonoCompartido = "+51999000111";
        Atencion titular = pasajeroGrupo(1L, "SGC-000000001", "Juan", "Perez", "ABC123", 10L, telefonoCompartido);
        Atencion pasajero2 = pasajeroGrupo(2L, "SGC-000000002", "Flor", "Vasquez", "ABC123", 10L, telefonoCompartido);
        Atencion pasajero3 = pasajeroGrupo(3L, "SGC-000000003", "Ana", "Ruiz", "ABC123", 10L, telefonoCompartido);

        VoucherGrupalRequest request = new VoucherGrupalRequest(
                List.of(1L, 2L, 3L), "grupo@test.com", true, null, null, null, null, null);

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(titular));
        when(atencionRepository.findById(2L)).thenReturn(Optional.of(pasajero2));
        when(atencionRepository.findById(3L)).thenReturn(Optional.of(pasajero3));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucherGrupal(any(), any(), eq(true))).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);
        when(s3StorageService.generarUrlConExpiracion(PDF_URL, 7)).thenReturn(PDF_URL + "?exp=7d");

        voucherService.generarYEnviarVoucherGrupal(request, 1L);

        // Antes del fix esto llamaba 3 veces (una por integrante) al mismo
        // número; ahora debe llamarse UNA sola vez en total.
        verify(whatsAppService, times(1)).enviarVoucherWhatsApp(
                eq(telefonoCompartido), any(), any(), anyString(),eq(IdiomaVoucherEnum.ES));
    }

    @Test
    @DisplayName("Voucher grupal manual: integrantes con teléfonos REALMENTE distintos siguen "
            + "recibiendo cada uno su propio WhatsApp (no se afecta por la deduplicación)")
    void generarYEnviarVoucherGrupal_conTelefonosDistintos_envianUnWhatsAppPorNumero() {
        Atencion titular = pasajeroGrupo(1L, "SGC-000000001", "Juan", "Perez", "ABC123", 10L, "+51999000111");
        Atencion acompanante = pasajeroGrupo(2L, "SGC-000000002", "Flor", "Vasquez", "ABC123", 10L, "+51999000222");

        VoucherGrupalRequest request = new VoucherGrupalRequest(
                List.of(1L, 2L), "grupo@test.com", true, null, null, null, null, null);

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(titular));
        when(atencionRepository.findById(2L)).thenReturn(Optional.of(acompanante));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucherGrupal(any(), any(), eq(true))).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);
        when(s3StorageService.generarUrlConExpiracion(PDF_URL, 7)).thenReturn(PDF_URL + "?exp=7d");

        voucherService.generarYEnviarVoucherGrupal(request, 1L);

        verify(whatsAppService, times(1)).enviarVoucherWhatsApp(
                eq("+51999000111"), eq("SGC-000000001"), eq("Juan Perez"), anyString(),eq(IdiomaVoucherEnum.ES));
        verify(whatsAppService, times(1)).enviarVoucherWhatsApp(
                eq("+51999000222"), eq("SGC-000000002"), eq("Flor Vasquez"), anyString(),eq(IdiomaVoucherEnum.ES));
    }

    @Test
    void generarYEnviarVoucherGrupal_sinCorreoDestino_usaCorreoDelTitular() {
        Atencion titular = pasajeroGrupo(1L, "SGC-000000001", "Juan", "Perez", "ABC123", 10L, null);

        VoucherGrupalRequest request = new VoucherGrupalRequest(List.of(1L), null, true,null,null,null,null,null);

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(titular));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucherGrupal(any(), any(), eq(true))).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);

        VoucherGrupalResponse response = voucherService.generarYEnviarVoucherGrupal(request, 1L);

        assertEquals("juan@test.com", response.correoDestino());
        assertEquals(List.of("SGC-000000001"), response.numerosVoucher()); // un solo pasajero, sin "(+N)"
    }

    // ══════════════════════════════════════════════════════════════════════════
    // reenviarPdf() — diferenciación de mensaje (reenvío por actualización)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reenviarPdf individual: usa emailService.reenviarVoucher() y "
            + "whatsAppService.reenviarVoucherWhatsApp() (mensaje de actualización), no los de primer envío")
    void reenviarPdf_individual_usaMetodosDeReenvioPorActualizacion() {
        Atencion atencion = pasajeroGrupo(1L, "SGC-000000001", "Juan", "Perez", "ABC123", 10L, null);
        atencion.setGrupoId(null);

        com.saasa.contingencias.domain.dto.request.EnvioEmailRequest request =
                new com.saasa.contingencias.domain.dto.request.EnvioEmailRequest(
                        "juan@test.com", "+51999000111", null, null);

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencion));

        voucherService.reenviarPdf(1L, request, 1L);

        verify(emailService).reenviarVoucher(eq(1L), eq("juan@test.com"), eq((List<String>) null), eq(1L));
        verify(whatsAppService).reenviarVoucherWhatsApp(eq(1L), eq("+51999000111"), eq(1L),any());
        // No debe usar los métodos de PRIMER envío (mensaje "asignados").
        verify(emailService, never()).enviarVoucher(any(), any(), any(), any(), any(),any());
        verify(whatsAppService, never()).enviarVoucherWhatsApp(any(), any(), any(), any(),any()
        );
    }

    @Test
    @DisplayName("reenviarPdf grupal: usa emailService.reenviarVoucherGrupal() (mensaje "
            + "\"actualizados\"), no enviarVoucherGrupal() (mensaje \"asignados\" del primer envío)")
    void reenviarPdf_grupal_usaReenviarVoucherGrupal() {
        Atencion titular = pasajeroGrupo(1L, "SGC-000000001", "Juan", "Perez", "ABC123", 10L, null);
        Atencion acompanante = pasajeroGrupo(2L, "SGC-000000002", "Flor", "Vasquez", "ABC123", 10L, null);
        titular.setGrupoId("grupo-1");
        acompanante.setGrupoId("grupo-1");

        com.saasa.contingencias.domain.dto.request.EnvioEmailRequest request =
                new com.saasa.contingencias.domain.dto.request.EnvioEmailRequest(
                        "grupo@test.com", null, null, null);

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(titular));
        when(atencionRepository.findByGrupoIdOrderByIdAsc("grupo-1"))
                .thenReturn(List.of(titular, acompanante));
        when(servicioAsignadoRepository.findByAtencionId(2L)).thenReturn(List.of());
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucherGrupal(any(), any(), anyBoolean())).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);

        voucherService.reenviarPdf(1L, request, 1L);

        verify(emailService).reenviarVoucherGrupal(
                eq("grupo@test.com"), eq((List<String>) null), eq("SGC-000000001 (+1)"),
                eq(PDF_BYTES), eq(List.of("Juan Perez", "Flor Vasquez")),eq(IdiomaVoucherEnum.ES));
        verify(emailService, never()).enviarVoucherGrupal(any(), any(), any(), any(), any(),any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // notificarDisponibilidad() — comportamiento no bloqueante
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void generarVoucherPdf_fallaNotificacion_noInterrumpeElFlujo() {
        // Arrange — atención con registroVueloDiario para activar notificación
        RegistroVueloDiario registroDiario = RegistroVueloDiario.builder()
                .id(10L).build();
        atencionBase.setRegistroVueloDiario(registroDiario);

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(atencionBase));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(pdfGeneratorService.generarVoucher(any(), any())).thenReturn(PDF_BYTES);
        when(s3StorageService.subirPdf(any(), anyString())).thenReturn(PDF_URL);
        when(atencionRepository.save(any())).thenReturn(atencionBase);
        doThrow(new RuntimeException("WebSocket caído"))
                .when(disponibilidadService).notificarCambioDisponibilidad(10L);

        // Act — no debe lanzar excepción aunque WebSocket falle
        VoucherResponse response = voucherService.generarVoucherPdf(1L);

        // Assert
        assertNotNull(response);  // el flujo principal terminó correctamente
        assertEquals(PDF_URL, response.pdfUrl());
    }
}

