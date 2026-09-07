package com.saasa.contingencias.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.security.JwtUtil;
import com.saasa.contingencias.config.security.ratelimit.LoginAttemptService;
import com.saasa.contingencias.domain.dto.request.VueloRequest;
import com.saasa.contingencias.domain.dto.response.CargaMasivaResponse;
import com.saasa.contingencias.domain.dto.response.VueloResponse;
import com.saasa.contingencias.domain.enumeration.ContingenciaEnum;
import com.saasa.contingencias.domain.repository.AuditoriaRepository;
import com.saasa.contingencias.service.IVueloService;
import com.saasa.contingencias.util.DateTimeUtil;
import com.saasa.contingencias.util.SecurityHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = VueloController.class)
@Import(TestSecurityConfig.class)           // ← seguridad mínima, sin JPA ni @Value
class VueloControllerTest {

    @Autowired MockMvc mockMvc;

    // ── Mocks del constructor de VueloController ────────────────────────────
    @MockBean IVueloService vueloService;
    @MockBean SecurityHelper securityHelper;   // ← VueloController lo usa en create/anular/etc.

    // ── Fix seguridad: JwtFilter necesita JwtUtil en su constructor ─────────
    @MockBean JwtUtil jwtUtil;

    // ── Fix ratelimit: LoginRateLimitFilter necesita estos dos beans ──────────
    @MockBean
    LoginAttemptService loginAttemptService;
    @MockBean
    AuditoriaRepository auditoriaRepository;// ← FALTABA, causa el crash de contexto

    // ObjectMapper con soporte para LocalDate (Java 8 Time API)
    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private LocalDate fechaFutura;

    @BeforeEach
    void setUp() {
        fechaFutura = DateTimeUtil.hoyEnLima().plusDays(5);
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/v1/vuelos — datos válidos, rol LIDER_SAASA → 201
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "LIDER_SAASA")
    void create_datosValidos_retorna201() throws Exception {
        VueloRequest req = new VueloRequest(
                "PlusUltra", "PU302", "LIM", "BOG",
                LocalDate.of(2026, 4, 15), ContingenciaEnum.CANCELACION, "Cancelado", null, null);

        VueloResponse resp = new VueloResponse(
                1L, "PlusUltra", "PU302", "LIM", "BOG",
                LocalDate.of(2026, 4, 15), "CANCELACION", "Cancelado",
                "ACTIVO", 1L, "Lider SAASA", null);

        // securityHelper.getUsuarioId() es llamado por el controller
        when(securityHelper.getUsuarioId(any())).thenReturn(1L);
        when(vueloService.create(any(), any())).thenReturn(resp);

        mockMvc.perform(post("/api/v1/vuelos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.codigoVuelo").value("PU302"))
                .andExpect(jsonPath("$.data.estado").value("ACTIVO"));
    }

    // ════════════════════════════════════════════════════════════════════════
    // PATCH /api/v1/vuelos/{id}/anular — rol LIDER_SAASA → 200
    // anular() no recibe UserDetails, solo el id del vuelo
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "LIDER_SAASA")
    void anular_retorna200() throws Exception {
        doNothing().when(vueloService).anular(1L);

        mockMvc.perform(patch("/api/v1/vuelos/1/anular").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/v1/vuelos — rol AGENTE_SAASA → 403
    // El endpoint tiene @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA')")
    // AGENTE_SAASA no está en esa lista → debe retornar 403.
    // Requiere @EnableMethodSecurity en TestSecurityConfig.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void create_rolAgente_retorna403() throws Exception {
        VueloRequest req = new VueloRequest(
                "PlusUltra", "PU302", "LIM", "BOG",
                LocalDate.of(2026, 4, 15), ContingenciaEnum.CANCELACION, "Cancelado", null, null);
        mockMvc.perform(post("/api/v1/vuelos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /vuelos — vuelo duplicado → 400 con mensaje de error")
    @WithMockUser(roles = "LIDER_SAASA")
    void create_vueloDuplicado_retorna400() throws Exception {
        VueloRequest req = new VueloRequest(
                "PlusUltra", "PU301", "LIM", "MAD",
                fechaFutura, ContingenciaEnum.CANCELACION, "", null, null);

        when(securityHelper.getUsuarioId(any())).thenReturn(1L);
        when(vueloService.create(any(), any()))
                .thenThrow(new BadRequestException(
                        "Ya existe el vuelo PU301 para la fecha " + fechaFutura));

        mockMvc.perform(post("/api/v1/vuelos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("PU301")));
    }

    @Test
    @DisplayName("PATCH /vuelos/1/anular — vuelo con atenciones → 400")
    @WithMockUser(roles = "LIDER_SAASA")
    void anular_vueloConAtenciones_retorna400() throws Exception {
        doThrow(new BadRequestException("No se puede anular: tiene atenciones asociadas"))
                .when(vueloService).anular(1L);

        mockMvc.perform(patch("/api/v1/vuelos/1/anular").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("atenciones")));
    }

    @Test
    @DisplayName("PATCH /vuelos/1/habilitar — vuelo ANULADO → 200 reactivado")
    @WithMockUser(roles = "LIDER_SAASA")
    void habilitar_retorna200() throws Exception {
        doNothing().when(vueloService).habilitar(1L);

        mockMvc.perform(patch("/api/v1/vuelos/1/habilitar").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PATCH /vuelos/1/habilitar — vuelo ya ACTIVO → 400")
    @WithMockUser(roles = "LIDER_SAASA")
    void habilitar_vueloYaActivo_retorna400() throws Exception {
        doThrow(new BadRequestException("El vuelo ya se encuentra ACTIVO"))
                .when(vueloService).habilitar(1L);

        mockMvc.perform(patch("/api/v1/vuelos/1/habilitar").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    //Carga masiva

    @Test
    @DisplayName("POST /carga-masiva — todas válidas → 200 con registrados=3, errores=[]")
    @WithMockUser(roles = "ADMINISTRADOR")
    void cargaMasiva_todasValidas_retorna200ConRegistrados() throws Exception {
        VueloResponse r1 = vueloResp(1L, "PU301", fechaFutura);
        VueloResponse r2 = vueloResp(2L, "PU302", fechaFutura.plusDays(1));
        VueloResponse r3 = vueloResp(3L, "IB200", fechaFutura.plusDays(2));
        CargaMasivaResponse resp = new CargaMasivaResponse(List.of(r1, r2, r3), List.of());

        when(securityHelper.getUsuarioId(any())).thenReturn(1L);
        when(vueloService.cargarDesdeExcel(any(), any(), any(), any())).thenReturn(resp);

        MockMultipartFile file = new MockMultipartFile(
                "archivo", "vuelos.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{0x50, 0x4B});  // bytes mínimos, el servicio está mockeado

        mockMvc.perform(multipart("/api/v1/vuelos/carga-masiva")
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.registrados").isArray())
                .andExpect(jsonPath("$.data.registrados.length()").value(3))
                .andExpect(jsonPath("$.data.errores").isArray())
                .andExpect(jsonPath("$.data.errores.length()").value(0));
    }

    @Test
    @DisplayName("POST /carga-masiva — import parcial → 200 con registrados=2, errores=1")
    @WithMockUser(roles = "ADMINISTRADOR")
    void cargaMasiva_importParcial_retorna200ConErrores() throws Exception {
        VueloResponse r1 = vueloResp(1L, "PU304", fechaFutura.plusDays(3));
        VueloResponse r2 = vueloResp(2L, "PU305", fechaFutura.plusDays(4));
        CargaMasivaResponse resp = new CargaMasivaResponse(
                List.of(r1, r2),
                List.of("Fila 5: el vuelo PU301 ya existe para la fecha " + fechaFutura +
                        " — omitido para evitar duplicado"));

        when(securityHelper.getUsuarioId(any())).thenReturn(1L);
        when(vueloService.cargarDesdeExcel(any(), any(), any(), any())).thenReturn(resp);

        MockMultipartFile file = new MockMultipartFile(
                "archivo", "vuelos.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{0x50, 0x4B});

        mockMvc.perform(multipart("/api/v1/vuelos/carga-masiva")
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.registrados.length()").value(2))
                .andExpect(jsonPath("$.data.errores.length()").value(1))
                .andExpect(jsonPath("$.data.errores[0]").value(
                        org.hamcrest.Matchers.containsString("PU301")));
    }

    @Test
    @DisplayName("POST /carga-masiva — rol LIDER_SAASA (sin permiso) → 403")
    @WithMockUser(roles = "LIDER_SAASA")
    void cargaMasiva_rolLider_retorna403() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "archivo", "vuelos.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{0x50, 0x4B});

        mockMvc.perform(multipart("/api/v1/vuelos/carga-masiva")
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helper
    // ════════════════════════════════════════════════════════════════════════

    private VueloResponse vueloResp(Long id, String codigo, LocalDate fecha) {
        return new VueloResponse(id, "PlusUltra", codigo, "LIM", "BOG",
                fecha, "CANCELACION", null, "ACTIVO", 1L, "Lider SAASA", null);
    }

}
