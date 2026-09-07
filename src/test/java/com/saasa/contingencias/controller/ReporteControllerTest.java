package com.saasa.contingencias.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saasa.contingencias.config.security.JwtUtil;
import com.saasa.contingencias.config.security.ratelimit.LoginAttemptService;
import com.saasa.contingencias.domain.dto.request.ActualizarPasajeroRequest;
import com.saasa.contingencias.domain.dto.response.ReporteDetalleResponse;
import com.saasa.contingencias.domain.repository.AuditoriaRepository;
import com.saasa.contingencias.service.IReporteService;
import com.saasa.contingencias.util.SecurityHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.saasa.contingencias.domain.dto.response.ReporteDetalleResponse;

// ════════════════════════════════════════════════════════════════════════
// PATCH /api/v1/reportes/{id}/pasajero
// Solo ADMINISTRADOR y LIDER_SAASA pueden editar nombre/correo/teléfono/PNR.
// ════════════════════════════════════════════════════════════════════════
@WebMvcTest(controllers = ReporteController.class)
@Import(TestSecurityConfig.class)
class ReporteControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean IReporteService reporteService;
    @MockBean SecurityHelper securityHelper;

    // ── Fix seguridad: evita que JwtFilter trate de leer @Value en test ──────
    @MockBean JwtUtil jwtUtil;

    // ── Fix ratelimit: LoginRateLimitFilter necesita estos dos beans ─────────
    @MockBean LoginAttemptService loginAttemptService;
    @MockBean AuditoriaRepository auditoriaRepository;

    private ActualizarPasajeroRequest reqValido() {
        return new ActualizarPasajeroRequest(
                "Ivan", "Perez Yumbato", "ivan@test.com", "+51987654321", "ABC123","ES");
    }

    // Response mínimo válido para simular el retorno del service
    private ReporteDetalleResponse respuestaMock() {
        return new ReporteDetalleResponse(
                1L, null, "SGC-000000001", "ABC123", null, "ACTIVO",
                "Ivan", "Perez Yumbato", "ivan@test.com", "+51987654321",
                1L, "PU301", "Plus Ultra", null, null, null,
                null, null, "LIM",
                null, null, null,
                BigDecimal.valueOf(100),
                "Dino Perez", null, null, null, null, null, null,
                null,null,null,null,null,null,null,"ES",null,null,null,null
        );
    }

    // ════════════════════════════════════════════════════════════════════
    // Roles PERMITIDOS → 200 OK
    // ════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "LIDER_SAASA","AGENTE_SAASA"})
    void actualizarPasajero_rolAutorizado_retorna200(String rol) throws Exception {
        when(securityHelper.getUsuarioId(any())).thenReturn(1L);
        when(reporteService.actualizarPasajero(eq(1L), any(), eq(1L)))
                .thenReturn(respuestaMock());

        mockMvc.perform(patch("/api/v1/reportes/1/pasajero")
                        .with(csrf())
                        .with(user("tester").roles(rol))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqValido())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nombrePasajero").value("Ivan"))
                .andExpect(jsonPath("$.data.pnr").value("ABC123"));
    }

    // ════════════════════════════════════════════════════════════════════
    // Roles NO PERMITIDOS → 403 Forbidden
    // Incluye AGENTE_SAASA, que sí puede editar servicios (hotel/transporte)
    // pero NO los datos del pasajero.
    // ════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {"PROVEEDOR", "LINEA_AEREA"})
    void actualizarPasajero_rolNoAutorizado_retorna403(String rol) throws Exception {
        mockMvc.perform(patch("/api/v1/reportes/1/pasajero")
                        .with(csrf())
                        .with(user("tester").roles(rol))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqValido())))
                .andExpect(status().isForbidden());
    }

    // ════════════════════════════════════════════════════════════════════
    // Body inválido (todos los campos null) → 400 Bad Request
    // ════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "ADMINISTRADOR")
    void actualizarPasajero_bodyVacio_retorna400() throws Exception {
        String jsonVacio = "{}"; // ningún campo enviado

        mockMvc.perform(patch("/api/v1/reportes/1/pasajero")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonVacio))
                .andExpect(status().isBadRequest());
    }

    // ════════════════════════════════════════════════════════════════════
    // Correo con formato inválido → 400 Bad Request
    // ════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "LIDER_SAASA")
    void actualizarPasajero_correoInvalido_retorna400() throws Exception {
        ActualizarPasajeroRequest reqInvalido = new ActualizarPasajeroRequest(
                null, null, "no-es-un-correo", null, null,null);

        mockMvc.perform(patch("/api/v1/reportes/1/pasajero")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqInvalido)))
                .andExpect(status().isBadRequest());
    }
}
