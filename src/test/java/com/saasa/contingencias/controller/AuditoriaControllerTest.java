package com.saasa.contingencias.controller;

import com.saasa.contingencias.config.security.JwtUtil;
import com.saasa.contingencias.config.security.ratelimit.LoginAttemptService;
import com.saasa.contingencias.domain.repository.AuditoriaRepository;
import com.saasa.contingencias.service.IAuditoriaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /api/v1/auditoria — los filtros deben llegar al servicio.
@WebMvcTest(controllers = AuditoriaController.class)
@Import(TestSecurityConfig.class)
class AuditoriaControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean IAuditoriaService auditoriaService;

    // Mismos mocks que ReporteControllerTest (JwtFilter y LoginRateLimitFilter)
    @MockBean JwtUtil jwtUtil;
    @MockBean LoginAttemptService loginAttemptService;
    @MockBean AuditoriaRepository auditoriaRepository;

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "LIDER_SAASA"})
    void listar_conTodosLosFiltros_losPasaAlServicio(String rol) throws Exception {
        when(auditoriaService.buscarConFiltros(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/auditoria")
                        .with(user("tester").roles(rol))
                        .param("buscar", "SGC")
                        .param("usuarioId", "0")
                        .param("modulo", "ATENCIONES")
                        .param("accion", "CREAR_ATENCION")
                        .param("fechaDesde", "2026-10-01")
                        .param("fechaHasta", "2026-10-11"))
                .andExpect(status().isOk());

        verify(auditoriaService).buscarConFiltros(
                eq("SGC"), eq(0L), eq("ATENCIONES"), eq("CREAR_ATENCION"),
                eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 10, 11)),
                any(Pageable.class));
    }

    @Test
    void listar_sinFiltros_funcionaConParametrosNulos() throws Exception {
        when(auditoriaService.buscarConFiltros(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/auditoria")
                        .with(user("tester").roles("ADMINISTRADOR")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AGENTE_SAASA", "PROVEEDOR", "LINEA_AEREA"})
    void listar_rolNoAutorizado_retorna403(String rol) throws Exception {
        mockMvc.perform(get("/api/v1/auditoria")
                        .with(user("tester").roles(rol)))
                .andExpect(status().isForbidden());

        verify(auditoriaService, never())
                .buscarConFiltros(any(), any(), any(), any(), any(), any(), any());
    }
}
