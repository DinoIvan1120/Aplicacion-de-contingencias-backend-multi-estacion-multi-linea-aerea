package com.saasa.contingencias.controller;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.exception.PdfGenerationException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.config.security.JwtUtil;
import com.saasa.contingencias.config.security.ratelimit.LoginAttemptService;
import com.saasa.contingencias.domain.repository.AuditoriaRepository;
import com.saasa.contingencias.service.IVoucherTicketService;
import com.saasa.contingencias.service.IVoucherTicketService.TicketPdf;
import com.saasa.contingencias.util.SecurityHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = VoucherTicketController.class)
@Import(TestSecurityConfig.class)
class VoucherTicketControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean IVoucherTicketService voucherTicketService;
    @MockBean SecurityHelper securityHelper;

    // Beans requeridos por la cadena de seguridad (mismo patrón que AtencionControllerTest)
    @MockBean JwtUtil jwtUtil;
    @MockBean LoginAttemptService loginAttemptService;
    @MockBean AuditoriaRepository auditoriaRepository;

    private static final String URL = "/api/v1/atenciones/{id}/voucher/ticket";
    private static final byte[] PDF = "%PDF-1.7 ticket".getBytes();

    // ════════════════════════════════════════════════════════════════
    // Éxito
    // ════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void generarTicket_sinAncho_usa80YDevuelvePdfInline() throws Exception {
        when(voucherTicketService.generarTicket(1L, 80))
                .thenReturn(new TicketPdf(PDF, "ticket-SGC-LIM-PU-000001.pdf", false, 1));

        mockMvc.perform(get(URL, 1L))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"ticket-SGC-LIM-PU-000001.pdf\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().bytes(PDF));

        verify(voucherTicketService).generarTicket(1L, 80);
    }

    @Test
    @WithMockUser(roles = "LIDER_SAASA")
    void generarTicket_ancho104_grupal_devuelveNombreDeGrupo() throws Exception {
        when(voucherTicketService.generarTicket(2L, 104))
                .thenReturn(new TicketPdf(PDF, "ticket-GRUPO-SGC-LIM-PU-000002.pdf", true, 4));

        mockMvc.perform(get(URL, 2L).param("ancho", "104"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"ticket-GRUPO-SGC-LIM-PU-000002.pdf\""));

        verify(voucherTicketService).generarTicket(2L, 104);
    }

    @Test
    @WithMockUser(roles = "ADMINISTRADOR")
    void generarTicket_administrador_retorna200() throws Exception {
        when(voucherTicketService.generarTicket(1L, 80))
                .thenReturn(new TicketPdf(PDF, "ticket-SGC-1.pdf", false, 1));

        mockMvc.perform(get(URL, 1L)).andExpect(status().isOk());
    }

    // ════════════════════════════════════════════════════════════════
    // Seguridad
    // ════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "LINEA_AEREA")
    void generarTicket_rolLineaAerea_retorna403() throws Exception {
        mockMvc.perform(get(URL, 1L)).andExpect(status().isForbidden());
        verify(voucherTicketService, never()).generarTicket(anyLong(), anyInt());
    }

    @Test
    @WithMockUser(roles = "PROVEEDOR")
    void generarTicket_rolProveedor_retorna403() throws Exception {
        mockMvc.perform(get(URL, 1L)).andExpect(status().isForbidden());
        verify(voucherTicketService, never()).generarTicket(anyLong(), anyInt());
    }

    @Test
    void generarTicket_sinAutenticar_retorna4xx() throws Exception {
        mockMvc.perform(get(URL, 1L)).andExpect(status().is4xxClientError());
        verify(voucherTicketService, never()).generarTicket(anyLong(), anyInt());
    }

    // ════════════════════════════════════════════════════════════════
    // Errores
    // ════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void generarTicket_anchoInvalido_retorna400() throws Exception {
        when(voucherTicketService.generarTicket(1L, 58))
                .thenThrow(new BadRequestException("El ancho del ticket debe ser 80 o 104 mm"));

        mockMvc.perform(get(URL, 1L).param("ancho", "58"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void generarTicket_atencionNoExiste_retorna404() throws Exception {
        when(voucherTicketService.generarTicket(99L, 80))
                .thenThrow(new RecursoNoEncontradoException("Atención no encontrada: 99"));

        mockMvc.perform(get(URL, 99L)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void generarTicket_atencionAnulada_retorna400() throws Exception {
        when(voucherTicketService.generarTicket(1L, 80))
                .thenThrow(new BadRequestException("No se puede imprimir el ticket de una atención anulada"));

        mockMvc.perform(get(URL, 1L)).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void generarTicket_errorAlGenerarPdf_retorna500() throws Exception {
        when(voucherTicketService.generarTicket(1L, 80))
                .thenThrow(new PdfGenerationException("Error al generar el ticket", new RuntimeException()));

        mockMvc.perform(get(URL, 1L)).andExpect(status().isInternalServerError());
    }
}