package com.saasa.contingencias.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saasa.contingencias.config.exception.AccesoDenegadoException;
import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.security.JwtUtil;
import com.saasa.contingencias.config.security.ratelimit.LoginAttemptService;
import com.saasa.contingencias.domain.dto.request.LoginRequest;
import com.saasa.contingencias.domain.dto.request.UsuarioRequest;
import com.saasa.contingencias.domain.dto.response.AuthResponse;
import com.saasa.contingencias.domain.dto.response.UsuarioResponse;
import com.saasa.contingencias.domain.enumeration.RolEnum;
import com.saasa.contingencias.domain.repository.AuditoriaRepository;
import com.saasa.contingencias.service.IAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test de integración HTTP de AuthController usando @WebMvcTest.
 *
 * Diferencia clave respecto a AuthServiceImplTest (unitario con mocks):
 * este test verifica el flujo completo petición → filtro de seguridad →
 * controller → GlobalExceptionHandler → respuesta JSON, tal como lo vería
 * un cliente real. En particular, prueba que /register sea accesible de
 * forma anónima (igual que en producción) y que el AccesoDenegadoException
 * lanzado por el service se traduzca correctamente en un 403 HTTP.
 */
@WebMvcTest(controllers = AuthController.class)
@Import(TestSecurityConfig.class)
class AuthControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean IAuthService authService;

    // ── Beans requeridos por la cadena de filtros de seguridad ──────────────
    @MockBean JwtUtil jwtUtil;
    @MockBean LoginAttemptService loginAttemptService;
    @MockBean AuditoriaRepository auditoriaRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ══════════════════════════════════════════════════════════════════════
    // LOGIN
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /auth/login — anónimo, credenciales válidas → 200")
    void login_anonimo_credencialesValidas_retorna200() throws Exception {
        // lineaAereaFija=true con estacionFijaId/lineaAereaFijaId resueltos:
        // caso típico de un Agente SAASA con un único par estación+línea aérea
        // asignado (no necesita elegir contexto en el selector del topbar).
        AuthResponse resp = new AuthResponse("jwt-token", "AGENTE_SAASA", "Ana", "Ruiz",
                86400000L, List.of(1L), true, 1L, 5L);
        when(authService.login(any(LoginRequest.class))).thenReturn(resp);

        LoginRequest req = new LoginRequest("u@test.com", null, "Pass1234");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").value("jwt-token"))
                .andExpect(jsonPath("$.data.rol").value("AGENTE_SAASA"));
    }

    @Test
    @DisplayName("POST /auth/login — credenciales inválidas → 400")
    void login_credencialesInvalidas_retorna400() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BadRequestException("Credenciales inválidas"));

        LoginRequest req = new LoginRequest("u@test.com", null, "WrongPass1");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Credenciales inválidas")));
    }

    @Test
    @DisplayName("POST /auth/login — password muy corta (Bean Validation) → 400")
    void login_passwordInvalida_retorna400PorValidacion() throws Exception {
        // Menos de 8 caracteres: viola @Size(min = 8) del DTO, ni siquiera llega al service
        LoginRequest req = new LoginRequest("u@test.com", null, "123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verify(authService, never()).login(any(LoginRequest.class));
    }

    // ══════════════════════════════════════════════════════════════════════
    // REGISTER — el endpoint que tuvo la vulnerabilidad crítica
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /auth/register — anónimo, sistema vacío → 201 (bootstrap primer admin)")
    void register_anonimo_sistemaVacio_retorna201() throws Exception {
        UsuarioResponse resp = new UsuarioResponse(
                1L, "Admin", "Root", "admin@test.com", "00000000",
                "EMP-001", "ADMINISTRADOR", 1, null);
        when(authService.register(any(UsuarioRequest.class))).thenReturn(resp);

        UsuarioRequest req = new UsuarioRequest(
                "Admin", "Root", "admin@test.com", "00000000", "EMP-001",
                RolEnum.ADMINISTRADOR, "Pass1234");

        // Sin @WithMockUser — simula una petición anónima real, igual que un
        // cliente sin token. Solo pasa porque TestSecurityConfig marca esta
        // ruta como permitAll(), igual que la SecurityConfig de producción.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rol").value("ADMINISTRADOR"));
    }

    @Test
    @DisplayName("POST /auth/register — anónimo, sistema con usuarios → 403 (AccesoDenegadoException del service)")
    void register_anonimo_conUsuariosExistentes_retorna403() throws Exception {
        when(authService.register(any(UsuarioRequest.class)))
                .thenThrow(new AccesoDenegadoException(
                        "Se requiere autenticación para registrar usuarios"));

        UsuarioRequest req = new UsuarioRequest(
                "Intruso", "Malicioso", "intruso@test.com", "99999999",
                "EMP-999", RolEnum.ADMINISTRADOR, "Pass1234");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /auth/register — con token pero sin rol ADMINISTRADOR → 403")
    @WithMockUser(roles = "AGENTE_SAASA")
    void register_tokenSinRolAdmin_retorna403() throws Exception {
        when(authService.register(any(UsuarioRequest.class)))
                .thenThrow(new AccesoDenegadoException(
                        "Solo el ADMINISTRADOR puede registrar nuevos usuarios"));

        UsuarioRequest req = new UsuarioRequest(
                "Nuevo", "Usuario", "nuevo@test.com", "11111111",
                "EMP-002", RolEnum.AGENTE_SAASA, "Pass1234");

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /auth/register — campos obligatorios vacíos → 400 (Bean Validation)")
    void register_camposVacios_retorna400() throws Exception {
        String jsonInvalido = """
                {"nombre":"","apellido":"","correo":"no-es-email",
                 "documento":"","codigoEmpleado":"","rol":null,"password":"123"}
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonInvalido))
                .andExpect(status().isBadRequest());

        verify(authService, never()).register(any(UsuarioRequest.class));
    }

    // ══════════════════════════════════════════════════════════════════════
    // FORGOT PASSWORD / RESET PASSWORD — flujo anónimo por diseño
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /auth/forgot-password — anónimo, siempre 200 (no revela existencia del usuario)")
    void forgotPassword_anonimo_retorna200() throws Exception {
        doNothing().when(authService).forgotPassword(any());

        String json = """
                {"correo":"cualquiera@test.com","dni":null}
                """;

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /auth/reset-password — código incorrecto → 400")
    void resetPassword_codigoIncorrecto_retorna400() throws Exception {
        doThrow(new BadRequestException("Código incorrecto"))
                .when(authService).resetPassword(any());

        String json = """
                {"correo":"u@test.com","dni":null,"codigo":"999999","nuevaPassword":"NuevaClave1"}
                """;

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Código incorrecto")));
    }
}
