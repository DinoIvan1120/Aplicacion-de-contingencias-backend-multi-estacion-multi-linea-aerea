package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.exception.PdfGenerationException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.enumeration.EstadoAtencionEnum;
import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.model.ServicioAsignado;
import com.saasa.contingencias.domain.repository.AtencionRepository;
import com.saasa.contingencias.domain.repository.ServicioAsignadoRepository;
import com.saasa.contingencias.service.IVoucherTicketService.TicketPdf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Test unitario de VoucherTicketServiceImpl: decide si el ticket es
 * individual o grupal y arma los servicios con las mismas reglas que el
 * voucher A4. El PDF en sí (TicketPdfRenderer) se mockea; su dibujo se
 * prueba en TicketPdfRendererTest.
 */
@ExtendWith(MockitoExtension.class)
class VoucherTicketServiceImplTest {

    @Mock AtencionRepository atencionRepository;
    @Mock ServicioAsignadoRepository servicioAsignadoRepository;
    @Mock TicketPdfRenderer ticketPdfRenderer;

    @InjectMocks VoucherTicketServiceImpl ticketService;

    private static final byte[] PDF = new byte[]{'%', 'P', 'D', 'F', '-'};

    private Atencion atencion(Long id, String correlativo, String grupoId) {
        return Atencion.builder()
                .id(id)
                .numeroCorrelativo(correlativo)
                .nombre("Pax" + id)
                .apellido("Test")
                .pnr("ABC123")
                .grupoId(grupoId)
                .estado(EstadoAtencionEnum.ACTIVO)
                .build();
    }

    private ServicioAsignado servicio(Long id, Atencion a) {
        return ServicioAsignado.builder()
                .id(id)
                .atencion(a)
                .tipoDetalle(TipoDetalleEnum.HOTEL)
                .cantidad(1)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Validaciones
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Ancho distinto de 80/104 → BadRequest sin consultar la BD")
    void generarTicket_anchoInvalido_lanzaBadRequest() {
        assertThrows(BadRequestException.class, () -> ticketService.generarTicket(1L, 58));
        verifyNoInteractions(atencionRepository, servicioAsignadoRepository, ticketPdfRenderer);
    }

    @Test
    @DisplayName("Atención inexistente → RecursoNoEncontrado")
    void generarTicket_atencionNoExiste_lanzaNoEncontrado() {
        when(atencionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class, () -> ticketService.generarTicket(99L, 80));
        verifyNoInteractions(ticketPdfRenderer);
    }

    @Test
    @DisplayName("Atención ANULADA → BadRequest, no se imprime ticket")
    void generarTicket_atencionAnulada_lanzaBadRequest() {
        Atencion anulada = atencion(1L, "SGC-1", null);
        anulada.setEstado(EstadoAtencionEnum.ANULADO);
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(anulada));

        assertThrows(BadRequestException.class, () -> ticketService.generarTicket(1L, 80));
        verifyNoInteractions(ticketPdfRenderer);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Individual
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Sin grupoId → ticket INDIVIDUAL con sus servicios propios")
    void generarTicket_sinGrupo_generaIndividual() {
        Atencion a = atencion(1L, "SGC-LIM-PU-000001", null);
        List<ServicioAsignado> servicios = List.of(servicio(10L, a));
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(a));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(servicios);
        when(ticketPdfRenderer.renderIndividual(a, servicios, 80)).thenReturn(PDF);

        TicketPdf ticket = ticketService.generarTicket(1L, 80);

        assertArrayEquals(PDF, ticket.contenido());
        assertEquals("ticket-SGC-LIM-PU-000001.pdf", ticket.nombreArchivo());
        assertFalse(ticket.grupal());
        assertEquals(1, ticket.pasajeros());
        verify(atencionRepository, never()).findByGrupoIdOrderByIdAsc(any());
        verify(ticketPdfRenderer, never()).renderGrupal(anyList(), anyMap(), anyBoolean(), anyInt());
    }

    @Test
    @DisplayName("grupoId con un solo integrante → ticket INDIVIDUAL")
    void generarTicket_grupoDeUno_generaIndividual() {
        Atencion a = atencion(1L, "SGC-1", "G-1");
        List<ServicioAsignado> servicios = List.of(servicio(10L, a));
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(a));
        when(atencionRepository.findByGrupoIdOrderByIdAsc("G-1")).thenReturn(List.of(a));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(servicios);
        when(ticketPdfRenderer.renderIndividual(a, servicios, 104)).thenReturn(PDF);

        TicketPdf ticket = ticketService.generarTicket(1L, 104);

        assertFalse(ticket.grupal());
        verify(ticketPdfRenderer).renderIndividual(a, servicios, 104);
    }

    @Test
    @DisplayName("Individual sin servicios propios pero con grupoId → usa los servicios del grupo (como el A4)")
    void generarTicket_individualSinServiciosPropios_usaServiciosDelGrupo() {
        Atencion a = atencion(1L, "SGC-1", "G-1");
        List<ServicioAsignado> delGrupo = List.of(servicio(10L, a));
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(a));
        when(atencionRepository.findByGrupoIdOrderByIdAsc("G-1")).thenReturn(List.of(a));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(servicioAsignadoRepository.findByAtencionGrupoId("G-1")).thenReturn(delGrupo);
        when(ticketPdfRenderer.renderIndividual(a, delGrupo, 80)).thenReturn(PDF);

        ticketService.generarTicket(1L, 80);

        verify(ticketPdfRenderer).renderIndividual(a, delGrupo, 80);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Grupal
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Grupo con acompañantes SIN servicios propios → GRUPAL compartido con los servicios del titular")
    @SuppressWarnings("unchecked")
    void generarTicket_grupoCompartido_generaGrupalCompartido() {
        Atencion titular = atencion(1L, "SGC-LIM-PU-000002", "G-1");
        Atencion acomp1 = atencion(2L, "SGC-LIM-PU-000003", "G-1");
        Atencion acomp2 = atencion(3L, "SGC-LIM-PU-000004", "G-1");
        List<Atencion> grupo = List.of(titular, acomp1, acomp2);
        List<ServicioAsignado> delTitular = List.of(servicio(10L, titular));

        when(atencionRepository.findById(2L)).thenReturn(Optional.of(acomp1)); // se pide con un acompañante
        when(atencionRepository.findByGrupoIdOrderByIdAsc("G-1")).thenReturn(grupo);
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(delTitular);
        when(servicioAsignadoRepository.findByAtencionId(2L)).thenReturn(List.of());
        when(servicioAsignadoRepository.findByAtencionId(3L)).thenReturn(List.of());
        when(ticketPdfRenderer.renderGrupal(eq(grupo), anyMap(), eq(true), eq(80))).thenReturn(PDF);

        TicketPdf ticket = ticketService.generarTicket(2L, 80);

        assertTrue(ticket.grupal());
        assertEquals(3, ticket.pasajeros());
        assertEquals("ticket-GRUPO-SGC-LIM-PU-000002.pdf", ticket.nombreArchivo(),
                "El nombre usa el correlativo del TITULAR aunque se pida con un acompañante");

        ArgumentCaptor<Map<Long, List<ServicioAsignado>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ticketPdfRenderer).renderGrupal(eq(grupo), captor.capture(), eq(true), eq(80));
        Map<Long, List<ServicioAsignado>> mapa = captor.getValue();
        assertEquals(delTitular, mapa.get(1L));
        assertEquals(delTitular, mapa.get(2L));
        assertEquals(delTitular, mapa.get(3L));
        verify(ticketPdfRenderer, never()).renderIndividual(any(), anyList(), anyInt());
    }

    @Test
    @DisplayName("Grupo con servicios propios por pasajero → GRUPAL independiente, cada uno con los suyos")
    @SuppressWarnings("unchecked")
    void generarTicket_grupoIndependiente_generaGrupalIndependiente() {
        Atencion p1 = atencion(1L, "SGC-1", "G-2");
        Atencion p2 = atencion(2L, "SGC-2", "G-2");
        List<Atencion> grupo = List.of(p1, p2);
        List<ServicioAsignado> deP1 = List.of(servicio(10L, p1));
        List<ServicioAsignado> deP2 = List.of(servicio(20L, p2));

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(p1));
        when(atencionRepository.findByGrupoIdOrderByIdAsc("G-2")).thenReturn(grupo);
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(deP1);
        when(servicioAsignadoRepository.findByAtencionId(2L)).thenReturn(deP2);
        when(ticketPdfRenderer.renderGrupal(eq(grupo), anyMap(), eq(false), eq(104))).thenReturn(PDF);

        TicketPdf ticket = ticketService.generarTicket(1L, 104);

        assertTrue(ticket.grupal());
        ArgumentCaptor<Map<Long, List<ServicioAsignado>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ticketPdfRenderer).renderGrupal(eq(grupo), captor.capture(), eq(false), eq(104));
        assertEquals(deP1, captor.getValue().get(1L));
        assertEquals(deP2, captor.getValue().get(2L));
    }

    @Test
    @DisplayName("Grupal: titular sin servicios propios → toma los del grupo por grupoId")
    @SuppressWarnings("unchecked")
    void generarTicket_grupoCompartidoTitularSinPropios_usaServiciosPorGrupoId() {
        Atencion titular = atencion(1L, "SGC-1", "G-3");
        Atencion acomp = atencion(2L, "SGC-2", "G-3");
        List<Atencion> grupo = List.of(titular, acomp);
        List<ServicioAsignado> delGrupo = List.of(servicio(10L, titular));

        when(atencionRepository.findById(1L)).thenReturn(Optional.of(titular));
        when(atencionRepository.findByGrupoIdOrderByIdAsc("G-3")).thenReturn(grupo);
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(servicioAsignadoRepository.findByAtencionId(2L)).thenReturn(List.of());
        when(servicioAsignadoRepository.findByAtencionGrupoId("G-3")).thenReturn(delGrupo);
        when(ticketPdfRenderer.renderGrupal(eq(grupo), anyMap(), eq(true), eq(80))).thenReturn(PDF);

        ticketService.generarTicket(1L, 80);

        ArgumentCaptor<Map<Long, List<ServicioAsignado>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ticketPdfRenderer).renderGrupal(eq(grupo), captor.capture(), eq(true), eq(80));
        assertEquals(delGrupo, captor.getValue().get(1L));
        assertEquals(delGrupo, captor.getValue().get(2L));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Errores del renderer
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Si el renderer falla → PdfGenerationException")
    void generarTicket_rendererFalla_lanzaPdfGenerationException() {
        Atencion a = atencion(1L, "SGC-1", null);
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(a));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(ticketPdfRenderer.renderIndividual(any(), anyList(), anyInt()))
                .thenThrow(new RuntimeException("iText explotó"));

        assertThrows(PdfGenerationException.class, () -> ticketService.generarTicket(1L, 80));
    }

    @Test
    @DisplayName("Solo lectura: nunca guarda nada en BD")
    void generarTicket_noGuardaNada() {
        Atencion a = atencion(1L, "SGC-1", null);
        when(atencionRepository.findById(1L)).thenReturn(Optional.of(a));
        when(servicioAsignadoRepository.findByAtencionId(1L)).thenReturn(List.of());
        when(ticketPdfRenderer.renderIndividual(any(), anyList(), anyInt())).thenReturn(PDF);

        ticketService.generarTicket(1L, 80);

        verify(atencionRepository, never()).save(any());
        verify(atencionRepository, never()).saveAll(anyList());
    }
}