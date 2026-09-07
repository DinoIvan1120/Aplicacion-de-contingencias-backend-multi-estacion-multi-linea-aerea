package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.enumeration.EstadoDetalleLoteEnum;
import com.saasa.contingencias.domain.enumeration.EstadoLoteEnum;
import com.saasa.contingencias.domain.model.CargaMasivaDetalle;
import com.saasa.contingencias.domain.model.CargaMasivaLote;
import com.saasa.contingencias.domain.repository.CargaMasivaDetalleRepository;
import com.saasa.contingencias.domain.repository.CargaMasivaLoteRepository;
import com.saasa.contingencias.service.ICargaMasivaProgresoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CargaMasivaProgresoServiceImplTest {

    @Mock CargaMasivaLoteRepository loteRepository;
    @Mock CargaMasivaDetalleRepository detalleRepository;

    private CargaMasivaProgresoServiceImpl progresoService;

    private CargaMasivaLote lote;

    @BeforeEach
    void setUp() {
        progresoService = new CargaMasivaProgresoServiceImpl(loteRepository, detalleRepository);
        lote = CargaMasivaLote.builder()
                .id(1L).loteId("lote-uuid").estado(EstadoLoteEnum.PROCESANDO)
                .procesados(0).exitosos(0).fallidos(0).build();
    }

    @Test
    @DisplayName("getLotePorLoteId — lote existente se retorna tal cual")
    void getLotePorLoteId_existente_retornaLote() {
        when(loteRepository.findByLoteId("lote-uuid")).thenReturn(Optional.of(lote));
        assertEquals(lote, progresoService.getLotePorLoteId("lote-uuid"));
    }

    @Test
    @DisplayName("getLotePorLoteId — inexistente lanza RecursoNoEncontradoException")
    void getLotePorLoteId_inexistente_lanzaNotFound() {
        when(loteRepository.findByLoteId("no-existe")).thenReturn(Optional.empty());
        assertThrows(RecursoNoEncontradoException.class,
                () -> progresoService.getLotePorLoteId("no-existe"));
    }

    @Test
    @DisplayName("marcarDetalleEnviado — actualiza estado del detalle y suma contadores del lote")
    void marcarDetalleEnviado_actualizaDetalleYContadores() {
        CargaMasivaDetalle d1 = CargaMasivaDetalle.builder()
                .id(10L).estado(EstadoDetalleLoteEnum.PENDIENTE).build();
        CargaMasivaDetalle d2 = CargaMasivaDetalle.builder()
                .id(11L).estado(EstadoDetalleLoteEnum.PENDIENTE).build();
        when(detalleRepository.findAllById(List.of(10L, 11L))).thenReturn(List.of(d1, d2));
        when(loteRepository.findById(1L)).thenReturn(Optional.of(lote));

        progresoService.marcarDetalleEnviado(1L, List.of(10L, 11L));

        assertEquals(EstadoDetalleLoteEnum.ENVIADO, d1.getEstado());
        assertEquals(EstadoDetalleLoteEnum.ENVIADO, d2.getEstado());
        assertNull(d1.getMensajeError());

        ArgumentCaptor<CargaMasivaLote> loteCaptor = ArgumentCaptor.forClass(CargaMasivaLote.class);
        verify(loteRepository).save(loteCaptor.capture());
        assertEquals(2, loteCaptor.getValue().getProcesados());
        assertEquals(2, loteCaptor.getValue().getExitosos());
        assertEquals(0, loteCaptor.getValue().getFallidos());
    }

    @Test
    @DisplayName("marcarDetalleError — actualiza estado ERROR con mensaje y suma fallidos")
    void marcarDetalleError_actualizaDetalleYContadores() {
        CargaMasivaDetalle d1 = CargaMasivaDetalle.builder()
                .id(10L).estado(EstadoDetalleLoteEnum.PENDIENTE).build();
        when(detalleRepository.findAllById(List.of(10L))).thenReturn(List.of(d1));
        when(loteRepository.findById(1L)).thenReturn(Optional.of(lote));

        progresoService.marcarDetalleError(1L, List.of(10L), "SMTP timeout");

        assertEquals(EstadoDetalleLoteEnum.ERROR, d1.getEstado());
        assertEquals("SMTP timeout", d1.getMensajeError());

        ArgumentCaptor<CargaMasivaLote> loteCaptor = ArgumentCaptor.forClass(CargaMasivaLote.class);
        verify(loteRepository).save(loteCaptor.capture());
        assertEquals(1, loteCaptor.getValue().getProcesados());
        assertEquals(0, loteCaptor.getValue().getExitosos());
        assertEquals(1, loteCaptor.getValue().getFallidos());
    }

    @Test
    @DisplayName("marcarDetalleEnviado y marcarDetalleError acumulan sobre llamadas sucesivas")
    void llamadasSucesivas_acumulanContadores() {
        when(loteRepository.findById(1L)).thenReturn(Optional.of(lote));
        when(detalleRepository.findAllById(anyList())).thenReturn(List.of());

        progresoService.marcarDetalleEnviado(1L, List.of(10L, 11L)); // +2 exitosos
        progresoService.marcarDetalleError(1L, List.of(12L), "error");   // +1 fallido

        assertEquals(3, lote.getProcesados());
        assertEquals(2, lote.getExitosos());
        assertEquals(1, lote.getFallidos());
    }

    @Test
    @DisplayName("finalizarLote — sin fallidos → COMPLETADO")
    void finalizarLote_sinFallidos_completado() {
        lote.setFallidos(0);
        lote.setExitosos(5);
        when(loteRepository.findById(1L)).thenReturn(Optional.of(lote));

        progresoService.finalizarLote(1L);

        assertEquals(EstadoLoteEnum.COMPLETADO, lote.getEstado());
    }

    @Test
    @DisplayName("finalizarLote — con éxitos y fallos → COMPLETADO_CON_ERRORES")
    void finalizarLote_conExitosYFallos_completadoConErrores() {
        lote.setFallidos(2);
        lote.setExitosos(3);
        when(loteRepository.findById(1L)).thenReturn(Optional.of(lote));

        progresoService.finalizarLote(1L);

        assertEquals(EstadoLoteEnum.COMPLETADO_CON_ERRORES, lote.getEstado());
    }

    @Test
    @DisplayName("finalizarLote — todos fallidos, ningún éxito → ERROR")
    void finalizarLote_todosFallidos_error() {
        lote.setFallidos(3);
        lote.setExitosos(0);
        when(loteRepository.findById(1L)).thenReturn(Optional.of(lote));

        progresoService.finalizarLote(1L);

        assertEquals(EstadoLoteEnum.ERROR, lote.getEstado());
    }

    @Test
    @DisplayName("marcarAtencionesCreadasBatch — actualiza TODOS los detalles del chunk en un solo saveAll y suma el contador de una vez")
    void marcarAtencionesCreadasBatch_actualizaTodosYSumaContadorDeUnaVez() {
        CargaMasivaDetalle d1 = CargaMasivaDetalle.builder()
                .id(10L).estado(EstadoDetalleLoteEnum.POR_CREAR).build();
        CargaMasivaDetalle d2 = CargaMasivaDetalle.builder()
                .id(11L).estado(EstadoDetalleLoteEnum.POR_CREAR).build();
        when(detalleRepository.findAllById(List.of(10L, 11L))).thenReturn(List.of(d1, d2));
        when(loteRepository.findById(1L)).thenReturn(Optional.of(lote));

        progresoService.marcarAtencionesCreadasBatch(1L, List.of(
                new ICargaMasivaProgresoService.AtencionCreadaInfo(10L, 500L, "SGC-500"),
                new ICargaMasivaProgresoService.AtencionCreadaInfo(11L, 501L, "SGC-501")));

        assertEquals(500L, d1.getAtencionId());
        assertEquals("SGC-500", d1.getCorrelativo());
        assertEquals(EstadoDetalleLoteEnum.PENDIENTE, d1.getEstado());
        assertEquals(501L, d2.getAtencionId());
        assertEquals("SGC-501", d2.getCorrelativo());

        verify(detalleRepository, times(1)).saveAll(anyList());

        ArgumentCaptor<CargaMasivaLote> loteCaptor = ArgumentCaptor.forClass(CargaMasivaLote.class);
        verify(loteRepository, times(1)).save(loteCaptor.capture());
        assertEquals(2, loteCaptor.getValue().getProcesados());
        assertEquals(2, loteCaptor.getValue().getExitosos());
        assertEquals(0, loteCaptor.getValue().getFallidos());
    }

    @Test
    @DisplayName("marcarAtencionesCreadasBatch — lista vacía o null no toca la BD")
    void marcarAtencionesCreadasBatch_listaVacia_noTocaBD() {
        progresoService.marcarAtencionesCreadasBatch(1L, List.of());
        progresoService.marcarAtencionesCreadasBatch(1L, null);
        verifyNoInteractions(detalleRepository, loteRepository);
    }

    @Test
    @DisplayName("marcarErroresCreacionBatch — actualiza TODOS los detalles del chunk en un solo saveAll y suma fallidos de una vez")
    void marcarErroresCreacionBatch_actualizaTodosYSumaFallidosDeUnaVez() {
        CargaMasivaDetalle d1 = CargaMasivaDetalle.builder()
                .id(20L).estado(EstadoDetalleLoteEnum.POR_CREAR).build();
        when(detalleRepository.findAllById(List.of(20L))).thenReturn(List.of(d1));
        when(loteRepository.findById(1L)).thenReturn(Optional.of(lote));

        progresoService.marcarErroresCreacionBatch(1L, List.of(
                new ICargaMasivaProgresoService.ErrorCreacionInfo(20L, "PNR duplicado")));

        assertEquals(EstadoDetalleLoteEnum.ERROR_CREACION, d1.getEstado());
        assertEquals("PNR duplicado", d1.getMensajeError());

        verify(detalleRepository, times(1)).saveAll(anyList());

        ArgumentCaptor<CargaMasivaLote> loteCaptor = ArgumentCaptor.forClass(CargaMasivaLote.class);
        verify(loteRepository, times(1)).save(loteCaptor.capture());
        assertEquals(1, loteCaptor.getValue().getProcesados());
        assertEquals(0, loteCaptor.getValue().getExitosos());
        assertEquals(1, loteCaptor.getValue().getFallidos());
    }

    @Test
    @DisplayName("marcarErroresCreacionBatch — lista vacía o null no toca la BD")
    void marcarErroresCreacionBatch_listaVacia_noTocaBD() {
        progresoService.marcarErroresCreacionBatch(1L, List.of());
        progresoService.marcarErroresCreacionBatch(1L, null);
        verifyNoInteractions(detalleRepository, loteRepository);
    }

    @Test
    @DisplayName("finalizarLote — lote inexistente lanza RecursoNoEncontradoException")
    void finalizarLote_loteInexistente_lanzaNotFound() {
        when(loteRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(RecursoNoEncontradoException.class,
                () -> progresoService.finalizarLote(99L));
    }
}