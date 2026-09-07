package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.domain.dto.request.GenerarVoucherRequest;
import com.saasa.contingencias.domain.dto.request.VoucherGrupalRequest;
import com.saasa.contingencias.domain.enumeration.EstadoDetalleLoteEnum;
import com.saasa.contingencias.domain.enumeration.EstadoLoteEnum;
import com.saasa.contingencias.domain.model.CargaMasivaDetalle;
import com.saasa.contingencias.domain.model.CargaMasivaLote;
import com.saasa.contingencias.domain.repository.CargaMasivaDetalleRepository;
import com.saasa.contingencias.service.IAtencionVoucherService;
import com.saasa.contingencias.service.ICargaMasivaProgresoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios de VoucherLoteOrchestratorImpl.
 *
 * NOTA: aquí se llama a procesarLoteAsync(...) directamente (sin pasar por
 * un ExecutorService real) — @Async no se activa en un test unitario puro;
 * lo que se prueba es la LÓGICA de agrupación y envío, no el hilo en sí.
 * El comportamiento asíncrono real se cubre con pruebas de integración /
 * manuales contra el AsyncConfig.
 */
@ExtendWith(MockitoExtension.class)
class VoucherLoteOrchestratorImplTest {

    @Mock CargaMasivaDetalleRepository detalleRepository;
    @Mock IAtencionVoucherService atencionVoucherService;
    @Mock ICargaMasivaProgresoService progresoService;

    private VoucherLoteOrchestratorImpl orchestrator;

    private CargaMasivaLote lote;

    @BeforeEach
    void setUp() {
        orchestrator = new VoucherLoteOrchestratorImpl(
                detalleRepository, atencionVoucherService, progresoService);
        lote = CargaMasivaLote.builder().id(1L).loteId("lote-uuid").estado(EstadoLoteEnum.PROCESANDO).build();

    }

    @Test
    @DisplayName("Lote inexistente → se aborta sin lanzar excepción ni tocar el resto")
    void loteInexistente_seAborta() {
        when(progresoService.getLotePorLoteId("no-existe"))
                .thenThrow(new RuntimeException("no encontrado"));

        assertDoesNotThrow(() -> orchestrator.procesarLoteAsync("no-existe", 1L));

        verifyNoInteractions(detalleRepository);
        verify(progresoService, never()).finalizarLote(any());
    }

    @Test
    @DisplayName("Pasajero individual (grupoId null) → envía voucher individual y marca ENVIADO")
    void pasajeroIndividual_enviaVoucherIndividual() {
        when(progresoService.getLotePorLoteId("lote-uuid")).thenReturn(lote);
        CargaMasivaDetalle individual = CargaMasivaDetalle.builder().estado(EstadoDetalleLoteEnum.PENDIENTE)
                .id(50L).atencionId(500L).grupoId(null).esTitular(false)
                .pnr("ABC123").correo("juan@test.com").build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(individual));

        orchestrator.procesarLoteAsync("lote-uuid", 1L);

        ArgumentCaptor<GenerarVoucherRequest> captor = ArgumentCaptor.forClass(GenerarVoucherRequest.class);
        verify(atencionVoucherService).generarYEnviarVoucher(eq(500L), captor.capture(), eq(1L));
        assertEquals("juan@test.com", captor.getValue().correoDestino());

        verify(progresoService).marcarDetalleEnviado(1L, List.of(50L));
        verify(atencionVoucherService, never()).generarYEnviarVoucherGrupal(any(), any());
        verify(progresoService).finalizarLote(1L);
    }

    @Test
    @DisplayName("Lote con ccDestinosJson y firmaPasajero → se propagan al GenerarVoucherRequest")
    void loteConCcYFirma_sePropaganAlRequest() {
        CargaMasivaLote loteConCc = CargaMasivaLote.builder()
                .id(1L).loteId("lote-uuid").estado(EstadoLoteEnum.PROCESANDO)
                .ccDestinosJson("aerolinea@test.com" + CargaMasivaLote.CC_SEPARATOR + "proveedor@test.com")
                .firmaPasajero("Juan Pérez Vásquez")
                .build();
        when(progresoService.getLotePorLoteId("lote-uuid")).thenReturn(loteConCc);
        CargaMasivaDetalle individual = CargaMasivaDetalle.builder().estado(EstadoDetalleLoteEnum.PENDIENTE)
                .id(50L).atencionId(500L).grupoId(null).correo("juan@test.com").build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(individual));

        orchestrator.procesarLoteAsync("lote-uuid", 1L);

        ArgumentCaptor<GenerarVoucherRequest> captor = ArgumentCaptor.forClass(GenerarVoucherRequest.class);
        verify(atencionVoucherService).generarYEnviarVoucher(eq(500L), captor.capture(), eq(1L));
        assertEquals(List.of("aerolinea@test.com", "proveedor@test.com"), captor.getValue().ccDestinos());
        assertEquals("Juan Pérez Vásquez", captor.getValue().firmaPasajero());
    }

    @Test
    @DisplayName("Lote sin ccDestinosJson (blank) → deserializarCcDestinos devuelve null, no lista vacía")
    void loteSinCc_deserializaANull() {
        CargaMasivaLote loteSinCc = CargaMasivaLote.builder()
                .id(1L).loteId("lote-uuid").estado(EstadoLoteEnum.PROCESANDO)
                .ccDestinosJson("   ") // blank, no null — cubre esa rama también
                .build();
        when(progresoService.getLotePorLoteId("lote-uuid")).thenReturn(loteSinCc);
        CargaMasivaDetalle individual = CargaMasivaDetalle.builder().estado(EstadoDetalleLoteEnum.PENDIENTE)
                .id(50L).atencionId(500L).grupoId(null).correo("juan@test.com").build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(individual));

        orchestrator.procesarLoteAsync("lote-uuid", 1L);

        ArgumentCaptor<GenerarVoucherRequest> captor = ArgumentCaptor.forClass(GenerarVoucherRequest.class);
        verify(atencionVoucherService).generarYEnviarVoucher(eq(500L), captor.capture(), eq(1L));
        assertNull(captor.getValue().ccDestinos());
    }

    @Test
    @DisplayName("Falla el envío individual → marca ERROR con el mensaje truncado y continúa")
    void fallaEnvioIndividual_marcaError() {
        when(progresoService.getLotePorLoteId("lote-uuid")).thenReturn(lote);
        CargaMasivaDetalle individual = CargaMasivaDetalle.builder().estado(EstadoDetalleLoteEnum.PENDIENTE)
                .id(50L).atencionId(500L).grupoId(null).correo("juan@test.com").build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(individual));
        when(atencionVoucherService.generarYEnviarVoucher(eq(500L), any(), eq(1L)))
                .thenThrow(new RuntimeException("SMTP timeout"));

        orchestrator.procesarLoteAsync("lote-uuid", 1L);

        ArgumentCaptor<String> mensajeCaptor = ArgumentCaptor.forClass(String.class);
        verify(progresoService).marcarDetalleError(eq(1L), eq(List.of(50L)), mensajeCaptor.capture());
        assertEquals("SMTP timeout", mensajeCaptor.getValue());
        verify(progresoService).finalizarLote(1L);
    }

    @Test
    @DisplayName("Falla con mensaje null → usa mensaje por defecto en vez de null")
    void fallaConMensajeNulo_usaMensajePorDefecto() {
        when(progresoService.getLotePorLoteId("lote-uuid")).thenReturn(lote);
        CargaMasivaDetalle individual = CargaMasivaDetalle.builder().estado(EstadoDetalleLoteEnum.PENDIENTE)
                .id(50L).atencionId(500L).grupoId(null).correo("juan@test.com").build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(individual));
        when(atencionVoucherService.generarYEnviarVoucher(eq(500L), any(), eq(1L)))
                .thenThrow(new RuntimeException((String) null));

        orchestrator.procesarLoteAsync("lote-uuid", 1L);

        ArgumentCaptor<String> mensajeCaptor = ArgumentCaptor.forClass(String.class);
        verify(progresoService).marcarDetalleError(eq(1L), eq(List.of(50L)), mensajeCaptor.capture());
        assertEquals("Error desconocido al generar/enviar el voucher", mensajeCaptor.getValue());
    }

    @Test
    @DisplayName("Grupo de 3 (mismo grupoId) → UN solo voucher grupal, marca los 3 detalles ENVIADO")
    void grupoDeTres_enviaUnSoloVoucherGrupal() {
        when(progresoService.getLotePorLoteId("lote-uuid")).thenReturn(lote);
        CargaMasivaDetalle titular = CargaMasivaDetalle.builder().estado(EstadoDetalleLoteEnum.PENDIENTE)
                .id(60L).atencionId(600L).grupoId("g1").esTitular(true)
                .pnr("XYZ789").correo("maria@test.com").build();
        CargaMasivaDetalle acomp1 = CargaMasivaDetalle.builder().estado(EstadoDetalleLoteEnum.PENDIENTE)
                .id(61L).atencionId(601L).grupoId("g1").esTitular(false).pnr("XYZ789").build();
        CargaMasivaDetalle acomp2 = CargaMasivaDetalle.builder().estado(EstadoDetalleLoteEnum.PENDIENTE)
                .id(62L).atencionId(602L).grupoId("g1").esTitular(false).pnr("XYZ789").build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(titular, acomp1, acomp2));

        orchestrator.procesarLoteAsync("lote-uuid", 1L);

        ArgumentCaptor<VoucherGrupalRequest> captor = ArgumentCaptor.forClass(VoucherGrupalRequest.class);
        verify(atencionVoucherService, times(1)).generarYEnviarVoucherGrupal(captor.capture(), eq(1L));
        assertEquals(List.of(600L, 601L, 602L), captor.getValue().atencionIds());
        assertEquals("maria@test.com", captor.getValue().correoDestino());

        verify(atencionVoucherService, never()).generarYEnviarVoucher(any(), any(), any());
        verify(progresoService).marcarDetalleEnviado(1L, List.of(60L, 61L, 62L));
    }

    @Test
    @DisplayName("Falla el envío grupal → los 3 detalles del grupo se marcan ERROR juntos")
    void fallaEnvioGrupal_marcaErrorATodoElGrupo() {
        when(progresoService.getLotePorLoteId("lote-uuid")).thenReturn(lote);
        CargaMasivaDetalle titular = CargaMasivaDetalle.builder().estado(EstadoDetalleLoteEnum.PENDIENTE)
                .id(60L).atencionId(600L).grupoId("g1").esTitular(true).correo("maria@test.com").build();
        CargaMasivaDetalle acomp1 = CargaMasivaDetalle.builder().estado(EstadoDetalleLoteEnum.PENDIENTE)
                .id(61L).atencionId(601L).grupoId("g1").esTitular(false).build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(titular, acomp1));
        when(atencionVoucherService.generarYEnviarVoucherGrupal(any(), eq(1L)))
                .thenThrow(new RuntimeException("Error generando PDF"));

        orchestrator.procesarLoteAsync("lote-uuid", 1L);

        verify(progresoService).marcarDetalleError(1L, List.of(60L, 61L), "Error generando PDF");
    }

    @Test
    @DisplayName("Mezcla de individuales y grupos → cada uno se procesa por su cauce correcto")
    void mezclaIndividualesYGrupos_procesaCadaUnoPorSuCauce() {
        when(progresoService.getLotePorLoteId("lote-uuid")).thenReturn(lote);
        CargaMasivaDetalle individual = CargaMasivaDetalle.builder().estado(EstadoDetalleLoteEnum.PENDIENTE)
                .id(50L).atencionId(500L).grupoId(null).correo("juan@test.com").build();
        CargaMasivaDetalle titular = CargaMasivaDetalle.builder().estado(EstadoDetalleLoteEnum.PENDIENTE)
                .id(60L).atencionId(600L).grupoId("g1").esTitular(true).correo("maria@test.com").build();
        CargaMasivaDetalle acomp = CargaMasivaDetalle.builder().estado(EstadoDetalleLoteEnum.PENDIENTE)
                .id(61L).atencionId(601L).grupoId("g1").esTitular(false).build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L))
                .thenReturn(List.of(individual, titular, acomp));

        orchestrator.procesarLoteAsync("lote-uuid", 1L);

        verify(atencionVoucherService, times(1)).generarYEnviarVoucher(eq(500L), any(), eq(1L));
        verify(atencionVoucherService, times(1)).generarYEnviarVoucherGrupal(any(), eq(1L));
        verify(progresoService).marcarDetalleEnviado(1L, List.of(50L));
        verify(progresoService).marcarDetalleEnviado(1L, List.of(60L, 61L));
        verify(progresoService).finalizarLote(1L);
    }
}
