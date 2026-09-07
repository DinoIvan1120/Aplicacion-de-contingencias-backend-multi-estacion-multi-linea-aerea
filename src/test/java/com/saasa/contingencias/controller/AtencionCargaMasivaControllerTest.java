package com.saasa.contingencias.controller;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.security.JwtUtil;
import com.saasa.contingencias.config.security.ratelimit.LoginAttemptService;
import com.saasa.contingencias.domain.dto.response.CargaMasivaAtencionResponse;
import com.saasa.contingencias.domain.dto.response.CargaMasivaPreviewResponse;
import com.saasa.contingencias.domain.dto.response.LoteEstadoResponse;
import com.saasa.contingencias.domain.repository.AuditoriaRepository;
import com.saasa.contingencias.service.IAtencionCargaMasivaService;
import com.saasa.contingencias.service.IAtencionCargaMasivaCreador;
import com.saasa.contingencias.util.SecurityHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AtencionCargaMasivaController.class)
@Import(TestSecurityConfig.class)
class AtencionCargaMasivaControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean IAtencionCargaMasivaService cargaMasivaService;
    @MockBean IAtencionCargaMasivaCreador cargaMasivaCreador;
    @MockBean SecurityHelper securityHelper;

    // Beans requeridos por la cadena de seguridad (mismo patrón que AtencionControllerTest)
    @MockBean JwtUtil jwtUtil;
    @MockBean LoginAttemptService loginAttemptService;
    @MockBean AuditoriaRepository auditoriaRepository;

    private MockMultipartFile archivoValido() {
        return new MockMultipartFile("archivo", "carga.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "contenido-simulado".getBytes());
    }

    // ════════════════════════════════════════════════════════════════
    // GET /restaurante/plantilla
    // ════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void descargarPlantilla_retornaXlsxDescargable() throws Exception {
        when(cargaMasivaService.generarPlantillaRestaurante()).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/v1/atenciones/carga-masiva/restaurante/plantilla"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("plantilla-carga-masiva-restaurante.xlsx")));
    }

    @Test
    @WithMockUser(roles = "LINEA_AEREA")
    void descargarPlantilla_rolSinPermiso_retorna403() throws Exception {
        mockMvc.perform(get("/api/v1/atenciones/carga-masiva/restaurante/plantilla"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(cargaMasivaService);
    }

    // ════════════════════════════════════════════════════════════════
    // POST /restaurante
    // ════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void cargarRestaurante_datosValidos_retorna200YDisparaProcesamientoAsync() throws Exception {
        when(securityHelper.getUsuarioId(any())).thenReturn(1L);
        CargaMasivaAtencionResponse resp = new CargaMasivaAtencionResponse(
                "lote-uuid", 3, 1, List.of());
        when(cargaMasivaService.cargarRestauranteDesdeExcel(any(), eq(10L), eq(20L), isNull(), isNull(), eq(1L), isNull()))
                .thenReturn(resp);
        when(cargaMasivaService.buscarLotePorIdempotencyKey(isNull())).thenReturn(java.util.Optional.empty());

        mockMvc.perform(multipart("/api/v1/atenciones/carga-masiva/restaurante")
                        .file(archivoValido())
                        .param("registroVueloDiarioId", "10")
                        .param("vueloRecursoId", "20")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.loteId").value("lote-uuid"))
                .andExpect(jsonPath("$.data.totalPasajeros").value(3))
                .andExpect(jsonPath("$.data.totalGrupos").value(1));

        verify(cargaMasivaCreador).crearAtencionesAsync("lote-uuid", 1L);
    }

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void cargarRestaurante_idempotencyKeyYaExistente_devuelveLoteExistenteYNoDisparaAsyncDeNuevo() throws Exception {
        CargaMasivaAtencionResponse existente = new CargaMasivaAtencionResponse(
                "lote-original", 500, 120, List.of());
        when(cargaMasivaService.buscarLotePorIdempotencyKey("clave-123"))
                .thenReturn(java.util.Optional.of(existente));

        mockMvc.perform(multipart("/api/v1/atenciones/carga-masiva/restaurante")
                        .file(archivoValido())
                        .param("registroVueloDiarioId", "10")
                        .param("vueloRecursoId", "20")
                        .param("idempotencyKey", "clave-123")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.loteId").value("lote-original"))
                .andExpect(jsonPath("$.data.totalPasajeros").value(500));

        verify(cargaMasivaService, never()).cargarRestauranteDesdeExcel(
                any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(cargaMasivaCreador);
    }

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void cargarRestaurante_servicioLanzaBadRequest_noDisparaProcesamientoAsync() throws Exception {
        when(securityHelper.getUsuarioId(any())).thenReturn(1L);
        when(cargaMasivaService.cargarRestauranteDesdeExcel(any(), eq(10L), eq(20L), isNull(), isNull(), eq(1L),isNull()))
                .thenThrow(new BadRequestException("Archivo inválido"));

        mockMvc.perform(multipart("/api/v1/atenciones/carga-masiva/restaurante")
                        .file(archivoValido())
                        .param("registroVueloDiarioId", "10")
                        .param("vueloRecursoId", "20")
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(cargaMasivaCreador);
    }

    @Test
    @WithMockUser(roles = "LINEA_AEREA")
    void cargarRestaurante_rolSinPermiso_retorna403() throws Exception {
        mockMvc.perform(multipart("/api/v1/atenciones/carga-masiva/restaurante")
                        .file(archivoValido())
                        .param("registroVueloDiarioId", "10")
                        .param("vueloRecursoId", "20")
                        .with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(cargaMasivaService);
        verifyNoInteractions(cargaMasivaCreador);
    }

    @Test
    void cargarRestaurante_sinAutenticar_retorna401o403() throws Exception {
        mockMvc.perform(multipart("/api/v1/atenciones/carga-masiva/restaurante")
                        .file(archivoValido())
                        .param("registroVueloDiarioId", "10")
                        .param("vueloRecursoId", "20")
                        .with(csrf()))
                .andExpect(status().is4xxClientError());
    }

    // ════════════════════════════════════════════════════════════════
    // GET /lotes/{loteId}
    // ════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void consultarLote_existente_retorna200ConEstado() throws Exception {
        LoteEstadoResponse resp = new LoteEstadoResponse(
                "lote-uuid", "PROCESANDO", 3, 1, 1, 0,
                List.of(new LoteEstadoResponse.DetalleItem(
                        "SGC-01", "ABC123", "Juan Perez", true, "ENVIADO", null)));
        when(cargaMasivaService.consultarEstadoLote("lote-uuid")).thenReturn(resp);

        mockMvc.perform(get("/api/v1/atenciones/carga-masiva/lotes/lote-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estado").value("PROCESANDO"))
                .andExpect(jsonPath("$.data.procesados").value(1))
                .andExpect(jsonPath("$.data.detalle[0].correlativo").value("SGC-01"));
    }

    @Test
    @WithMockUser(roles = "LIDER_SAASA")
    void consultarLote_rolLider_permiteAcceso() throws Exception {
        LoteEstadoResponse resp = new LoteEstadoResponse(
                "lote-uuid", "COMPLETADO", 1, 1, 1, 0, List.of());
        when(cargaMasivaService.consultarEstadoLote("lote-uuid")).thenReturn(resp);

        mockMvc.perform(get("/api/v1/atenciones/carga-masiva/lotes/lote-uuid"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void previsualizarRestaurante_datosValidos_retorna200ConGrupos() throws Exception {
        CargaMasivaPreviewResponse.GrupoPreview grupo = new CargaMasivaPreviewResponse.GrupoPreview(
                "ABC123", "JUAN PEREZ", "juan@test.com", "+51987654321",
                1, List.of("JUAN PEREZ"), 5, true, true, false);
        CargaMasivaPreviewResponse resp = new CargaMasivaPreviewResponse(
                1, 1, List.of(grupo), List.of(), 50, 5, false);

        when(cargaMasivaService.previsualizarRestauranteDesdeExcel(any(), eq(10L), eq(20L)))
                .thenReturn(resp);

        mockMvc.perform(multipart("/api/v1/atenciones/carga-masiva/restaurante/preview")
                        .file(archivoValido())
                        .param("registroVueloDiarioId", "10")
                        .param("vueloRecursoId", "20")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalPasajeros").value(1))
                .andExpect(jsonPath("$.data.totalGrupos").value(1))
                .andExpect(jsonPath("$.data.excedeCapacidad").value(false))
                .andExpect(jsonPath("$.data.grupos[0].pnr").value("ABC123"))
                .andExpect(jsonPath("$.data.grupos[0].nombreTitular").value("JUAN PEREZ"));

        // El preview NUNCA debe disparar el envío de vouchers en background
        verifyNoInteractions(cargaMasivaCreador);
    }

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void previsualizarRestaurante_excedeCapacidad_retorna200ConFlagYSinLanzar() throws Exception {
        CargaMasivaPreviewResponse resp = new CargaMasivaPreviewResponse(
                1, 1, List.of(), List.of(), 2, 10, true);

        when(cargaMasivaService.previsualizarRestauranteDesdeExcel(any(), eq(10L), eq(20L)))
                .thenReturn(resp);

        mockMvc.perform(multipart("/api/v1/atenciones/carga-masiva/restaurante/preview")
                        .file(archivoValido())
                        .param("registroVueloDiarioId", "10")
                        .param("vueloRecursoId", "20")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.excedeCapacidad").value(true))
                .andExpect(jsonPath("$.data.capacidadDisponible").value(2))
                .andExpect(jsonPath("$.data.totalPaxSolicitado").value(10));
    }

    @Test
    @WithMockUser(roles = "AGENTE_SAASA")
    void previsualizarRestaurante_servicioLanzaBadRequest_retorna400() throws Exception {
        when(cargaMasivaService.previsualizarRestauranteDesdeExcel(any(), eq(10L), eq(20L)))
                .thenThrow(new BadRequestException("Solo se aceptan archivos .xlsx"));

        mockMvc.perform(multipart("/api/v1/atenciones/carga-masiva/restaurante/preview")
                        .file(archivoValido())
                        .param("registroVueloDiarioId", "10")
                        .param("vueloRecursoId", "20")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "LINEA_AEREA")
    void previsualizarRestaurante_rolSinPermiso_retorna403() throws Exception {
        mockMvc.perform(multipart("/api/v1/atenciones/carga-masiva/restaurante/preview")
                        .file(archivoValido())
                        .param("registroVueloDiarioId", "10")
                        .param("vueloRecursoId", "20")
                        .with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(cargaMasivaService);
    }

    @Test
    void previsualizarRestaurante_sinAutenticar_retorna401o403() throws Exception {
        mockMvc.perform(multipart("/api/v1/atenciones/carga-masiva/restaurante/preview")
                        .file(archivoValido())
                        .param("registroVueloDiarioId", "10")
                        .param("vueloRecursoId", "20")
                        .with(csrf()))
                .andExpect(status().is4xxClientError());
    }

}
