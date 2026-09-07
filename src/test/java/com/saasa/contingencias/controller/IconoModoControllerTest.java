package com.saasa.contingencias.controller;

import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.config.security.JwtUtil;
import com.saasa.contingencias.config.security.ratelimit.LoginAttemptService;
import com.saasa.contingencias.domain.repository.AuditoriaRepository;
import com.saasa.contingencias.service.IIconoModoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * GET abierto a cualquier autenticado (solo lectura de un asset visual,
 * igual que EstacionController#obtenerUrlFoto). POST reservado al
 * Administrador Global vía @PreAuthorize("hasRole('ADMINISTRADOR') and
 * @estacionContext.esAdministradorGlobal()").
 *
 * @estacionContext se resuelve por SpEL contra el bean real
 * "estacionContext" — @WebMvcTest NO lo registra por defecto (solo carga
 * controllers/filtros, no @Component genéricos). Importar la CLASE
 * directamente (@Import(EstacionContext.class)) NO alcanza: el
 * TypeExcludeFilter de @WebMvcTest igual la excluye por ser un @Component
 * "de negocio" común. La forma que sí funciona es declararla con @Bean
 * dentro de una @TestConfiguration anidada — @TestConfiguration está
 * explícitamente exenta de ese filtro (es exactamente para esto que existe).
 *
 * Con @WithMockUser el principal autenticado no es un AuthenticatedPrincipal
 * real, así que EstacionContext.scopesActuales() cae a lista vacía y
 * esAdministradorGlobal() resuelve a true; por eso alcanza con el rol para
 * probar el camino feliz, y con un rol distinto para probar el 403.
 */
@WebMvcTest(controllers = IconoModoController.class)
@Import({TestSecurityConfig.class, IconoModoControllerTest.EstacionContextTestConfig.class})
class IconoModoControllerTest {

    @TestConfiguration
    static class EstacionContextTestConfig {
        @Bean
        EstacionContext estacionContext() {
            return new EstacionContext();
        }
    }

    @Autowired MockMvc mockMvc;

    @MockBean IIconoModoService iconoModoService;

    // ── Fix seguridad/ratelimit: mismos mocks que el resto de los *ControllerTest ──
    @MockBean JwtUtil jwtUtil;
    @MockBean LoginAttemptService loginAttemptService;
    @MockBean AuditoriaRepository auditoriaRepository;

    // ════════════════════════════════════════════════════════════════════
    // GET /api/v1/config/iconos-modo/{clave}
    // ════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void obtenerUrl_iconoExistente_retorna200ConLaUrl() throws Exception {
        when(iconoModoService.obtenerUrlIcono("GESTIONAR")).thenReturn("https://s3-firmada/gestionar.png");

        mockMvc.perform(get("/api/v1/config/iconos-modo/{clave}", "GESTIONAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("https://s3-firmada/gestionar.png"));
    }

    @Test
    @WithMockUser(roles = "PROVEEDOR")
    void obtenerUrl_sinIconoSubido_retorna200ConDataNull() throws Exception {
        when(iconoModoService.obtenerUrlIcono("OPERAR")).thenReturn(null);

        mockMvc.perform(get("/api/v1/config/iconos-modo/{clave}", "OPERAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    // Cualquier rol autenticado puede leer — no lleva @PreAuthorize.
    @Test
    @WithMockUser(roles = "LINEA_AEREA")
    void obtenerUrl_cualquierRolAutenticado_retorna200() throws Exception {
        when(iconoModoService.obtenerUrlIcono("OPERAR")).thenReturn("https://s3-firmada/operar.jpg");

        mockMvc.perform(get("/api/v1/config/iconos-modo/{clave}", "OPERAR"))
                .andExpect(status().isOk());
    }

    // ════════════════════════════════════════════════════════════════════
    // POST /api/v1/config/iconos-modo/{clave} — subir ícono
    // ════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "ADMINISTRADOR")
    void subirIcono_administradorGlobal_retorna200ConLaNuevaUrl() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "icono.png", "image/png", "contenido-fake".getBytes());

        when(iconoModoService.subirIcono(eq("GESTIONAR"), any(), eq("image/png")))
                .thenReturn("https://s3-firmada/gestionar-nuevo.png");

        mockMvc.perform(multipart("/api/v1/config/iconos-modo/{clave}", "GESTIONAR")
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("https://s3-firmada/gestionar-nuevo.png"));

        verify(iconoModoService).subirIcono(eq("GESTIONAR"), any(), eq("image/png"));
    }

    // Rol sin permisos → 403. hasRole('ADMINISTRADOR') ya lo bloquea antes
    // de siquiera evaluar @estacionContext.esAdministradorGlobal().
    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void subirIcono_rolNoAutorizado_retorna403() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "icono.png", "image/png", "contenido-fake".getBytes());

        mockMvc.perform(multipart("/api/v1/config/iconos-modo/{clave}", "OPERAR")
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "LIDER_SAASA")
    void subirIcono_liderSaasa_retorna403() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "icono.png", "image/png", "contenido-fake".getBytes());

        mockMvc.perform(multipart("/api/v1/config/iconos-modo/{clave}", "OPERAR")
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}