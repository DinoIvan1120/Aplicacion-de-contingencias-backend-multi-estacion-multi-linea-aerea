package com.saasa.contingencias.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saasa.contingencias.config.security.JwtUtil;
import com.saasa.contingencias.config.security.ratelimit.LoginAttemptService;
import com.saasa.contingencias.domain.dto.request.AtencionRequest;
import com.saasa.contingencias.domain.dto.request.GenerarVoucherRequest;
import com.saasa.contingencias.domain.dto.request.VoucherGrupalRequest;
import com.saasa.contingencias.domain.dto.response.AtencionResponse;
import com.saasa.contingencias.domain.dto.response.VoucherGrupalResponse;
import com.saasa.contingencias.domain.dto.response.VoucherResponse;
import com.saasa.contingencias.domain.repository.AuditoriaRepository;
import com.saasa.contingencias.service.*;
import com.saasa.contingencias.service.impl.BoardingPassImageService;
import com.saasa.contingencias.util.SecurityHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AtencionController.class)
@Import(TestSecurityConfig.class)
class AtencionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    // ── Mocks de servicios (todos los que el constructor de AtencionController requiere) ──
    @MockBean IAtencionService atencionService;
    @MockBean IAtencionVoucherService atencionVoucherService;    // ← FALTABA
    @MockBean IBoardingPassService boardingPassService;
    @MockBean BoardingPassImageService boardingPassImageService;

    // ── SecurityHelper mockeado como componente completo ─────────────────────
    // El controller lo usa en todos los endpoints con @AuthenticationPrincipal.
    // Al mockearlo, evitamos que Spring intente construirlo con UsuarioRepository.
    @MockBean SecurityHelper securityHelper;                     // ← FALTABA

    // ── Fix seguridad: evita que JwtFilter trate de leer @Value en test ──────
    @MockBean JwtUtil jwtUtil;

    // ── Fix ratelimit: LoginRateLimitFilter necesita estos dos beans ──────────
    // @WebMvcTest no carga @Service ni @Repository JPA automáticamente,
    // por eso Spring no puede construir LoginRateLimitFilter sin estos mocks.
    @MockBean
    LoginAttemptService loginAttemptService;
    @MockBean
    AuditoriaRepository auditoriaRepository;

    // ════════════════════════════════════════════════════════════════════════
    // Helper para construir un AtencionRequest válido.
    // PNR debe ser exactamente 6 caracteres alfanuméricos en mayúsculas
    // según @Pattern(regexp="^[A-Z0-9]{6}$") en AtencionRequest.
    // ════════════════════════════════════════════════════════════════════════
    private AtencionRequest reqValido(String pnr, long vueloId) {
        return new AtencionRequest(
                "Juan",             // 1 nombre
                "Perez",            // 2 apellido
                pnr,                // 3 pnr
                "jp@test.com",      // 4 correo
                null,               // 5 telefono (opcional)
                vueloId,            // 6 vueloId
                1L,                 // 7 registroVueloDiarioId
                null,               // 8 codigoBarras
                null,               // 9 fechaEmision (LocalDate)
                null,               // 10 lugarEmision (String)
                null,               // 11 grupoId (String)
                null                // 12 firmaPasajero (String)
        );
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/v1/atenciones — datos válidos → 201
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void create_datosValidos_retorna201() throws Exception {
        AtencionRequest req = reqValido("ABC123", 1L);

        AtencionResponse resp = new AtencionResponse(
                1L,
                "SGC-000001000",
                1L,
                "PU302",
                "Juan",
                "Perez",
                "ABC123",
                "jp@test.com",
                BigDecimal.ZERO,
                null,
                null,
                "ACTIVO",
                "Agente Uno",
                null,
                null,
                null,null,null,null
        );

        // SecurityHelper.getUsuarioId() es llamado por el controller
        // para resolver el ID del usuario autenticado.
        when(securityHelper.getUsuarioId(any())).thenReturn(1L);
        when(atencionService.create(any(), any())).thenReturn(resp);

        mockMvc.perform(post("/api/v1/atenciones")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pnr").value("ABC123"))
                .andExpect(jsonPath("$.data.numeroCorrelativo").value("SGC-000001000"));
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/v1/atenciones — PNR inválido → 400
    // "BAD!" tiene 4 chars y carácter especial, falla @Pattern y @Size.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void create_pnrInvalido_retorna400() throws Exception {
        AtencionRequest req = new AtencionRequest(
                "Juan",
                "Perez",
                "BAD!",      // ← 4 chars con '!', falla validación
                "jp@test.com",
                null,
                1L,
                1L,
                null, null, null,null,null
        );

        mockMvc.perform(post("/api/v1/atenciones")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/v1/atenciones — rol sin permisos → 403
    // El endpoint tiene @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    // LINEA_AEREA no está en esa lista → debe retornar 403.
    // Requiere @EnableMethodSecurity en TestSecurityConfig para que
    // @PreAuthorize sea evaluado durante los tests.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "LINEA_AEREA")
    void create_rolNoAutorizado_retorna403() throws Exception {
        AtencionRequest req = reqValido("ABC123", 1L);

        mockMvc.perform(post("/api/v1/atenciones")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/v1/atenciones — firmaPasajero como imagen base64 (canvas)
    // ────────────────────────────────────────────────────────────────────────
    // NUEVO: firmaPasajero pasó de ser un nombre tecleado (@Size max=150) a
    // ser una imagen PNG en base64 (data URL) dibujada en un canvas. Una firma
    // real fácilmente supera los 150 caracteres que antes causaban un 400;
    // este test confirma que ese límite obsoleto ya no bloquea el flujo.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void create_firmaPasajeroBase64Larga_retorna201() throws Exception {
        // Data URL realista de una firma dibujada: bastante más larga que
        // los 150 caracteres del antiguo límite (@Size max=150), pero muy
        // por debajo del nuevo tope de sanidad (2_000_000).
        String firmaBase64 = "data:image/png;base64," + "A".repeat(5_000);

        AtencionRequest req = new AtencionRequest(
                "Juan", "Perez", "ABC123", "jp@test.com",
                null, 1L, 1L,
                null, null, null, null,
                firmaBase64
        );

        AtencionResponse resp = new AtencionResponse(
                1L, "SGC-000001000", 1L, "PU302",
                "Juan", "Perez", "ABC123", "jp@test.com",
                BigDecimal.ZERO, null, null, "ACTIVO", "Agente Uno",
                null, null, null, null, null, null
        );

        when(securityHelper.getUsuarioId(any())).thenReturn(1L);
        when(atencionService.create(any(), any())).thenReturn(resp);

        mockMvc.perform(post("/api/v1/atenciones")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/v1/atenciones — firmaPasajero excede el tope de sanidad → 400
    // Cubre el nuevo @Size(max = 2_000_000) en AtencionRequest.firmaPasajero.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void create_firmaPasajeroExcedeTamanoMaximo_retorna400() throws Exception {
        String firmaDemasiadoLarga = "A".repeat(2_000_001);

        AtencionRequest req = new AtencionRequest(
                "Juan", "Perez", "ABC123", "jp@test.com",
                null, 1L, 1L,
                null, null, null, null,
                firmaDemasiadoLarga
        );

        mockMvc.perform(post("/api/v1/atenciones")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/v1/atenciones/{id}/voucher/generar-y-enviar
    // firmaPasajero excede el tope de sanidad → 400
    // Cubre el nuevo @Size(max = 2_000_000) en GenerarVoucherRequest.firmaPasajero,
    // agregado para blindar este endpoint también cuando se llama directamente
    // (sin pasar antes por el registro inicial de la atención en POST /atenciones).
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void generarYEnviarVoucher_firmaPasajeroExcedeTamanoMaximo_retorna400() throws Exception {
        GenerarVoucherRequest req = new GenerarVoucherRequest(
                "jp@test.com",
                List.of(),
                "A".repeat(2_000_001),
                "ES",
                null,
                null
        );

        mockMvc.perform(post("/api/v1/atenciones/{id}/voucher/generar-y-enviar", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void generarYEnviarVoucher_firmaPasajeroBase64Valida_retorna200() throws Exception {
        GenerarVoucherRequest req = new GenerarVoucherRequest(
                "jp@test.com",
                List.of(),
                "data:image/png;base64," + "A".repeat(5_000),
                "ES",
                null,
                null
        );

        when(securityHelper.getUsuarioId(any())).thenReturn(1L);
        when(atencionVoucherService.generarYEnviarVoucher(any(), any(), any()))
                .thenReturn(VoucherResponse.generadoYEnviado("SGC-000001000", "https://s3/voucher.pdf", "jp@test.com"));

        mockMvc.perform(post("/api/v1/atenciones/{id}/voucher/generar-y-enviar", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/v1/atenciones/voucher-grupal/generar-y-enviar
    // firmaPasajero excede el tope de sanidad → 400
    // Cubre el nuevo @Size(max = 2_000_000) en VoucherGrupalRequest.firmaPasajero.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void generarYEnviarVoucherGrupal_firmaPasajeroExcedeTamanoMaximo_retorna400() throws Exception {
        VoucherGrupalRequest req = new VoucherGrupalRequest(
                List.of(1L, 2L),
                "jp@test.com",
                true,
                List.of(),
                "A".repeat(2_000_001),
                "ES",
                null,
                null
        );

        mockMvc.perform(post("/api/v1/atenciones/voucher-grupal/generar-y-enviar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void generarYEnviarVoucherGrupal_firmaPasajeroBase64Valida_retorna200() throws Exception {
        VoucherGrupalRequest req = new VoucherGrupalRequest(
                List.of(1L, 2L),
                "jp@test.com",
                true,
                List.of(),
                "data:image/png;base64," + "A".repeat(5_000),
                "ES",
                null,
                null
        );

        when(securityHelper.getUsuarioId(any())).thenReturn(1L);
        when(atencionVoucherService.generarYEnviarVoucherGrupal(any(), any()))
                .thenReturn(VoucherGrupalResponse.generadoYEnviado(
                        List.of("SGC-000001000", "SGC-000001001"), "https://s3/voucher.pdf", "jp@test.com"));

        mockMvc.perform(post("/api/v1/atenciones/voucher-grupal/generar-y-enviar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}