package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.security.JwtUtil;
import com.saasa.contingencias.config.security.ratelimit.LoginAttemptService;
import com.saasa.contingencias.controller.TestSecurityConfig;
import com.saasa.contingencias.controller.VoucherRedirectController;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.repository.AtencionRepository;
import com.saasa.contingencias.domain.repository.AuditoriaRepository;
import com.saasa.contingencias.service.IS3StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = VoucherRedirectController.class)
@Import(TestSecurityConfig.class)
class VoucherRedirectControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean AtencionRepository atencionRepository;
    @MockBean IS3StorageService s3StorageService;

    @MockBean JwtUtil jwtUtil;
    @MockBean LoginAttemptService loginAttemptService;
    @MockBean AuditoriaRepository auditoriaRepository;

    private Atencion atencionConPdf() {
        Atencion a = new Atencion();
        a.setPdfUrl("prd/vouchers/2026/SGC-000000236.pdf");
        return a;
    }

    @Test
    void resolverVoucher_correlativoValido_redirige302AUrlFirmada() throws Exception {
        String urlFirmada = "https://integrityflow-apps-storage.s3.us-east-2.amazonaws.com/"
                + "prd/vouchers/2026/SGC-000000236.pdf?X-Amz-Signature=xyz";

        when(atencionRepository.findByNumeroCorrelativo("SGC-000000236"))
                .thenReturn(Optional.of(atencionConPdf()));
        when(s3StorageService.generarUrlFirmada("prd/vouchers/2026/SGC-000000236.pdf"))
                .thenReturn(urlFirmada);

        mockMvc.perform(get("/v/SGC-000000236"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, urlFirmada));
    }

    @Test
    void resolverVoucher_correlativoInexistente_retorna404() throws Exception {
        when(atencionRepository.findByNumeroCorrelativo(eq("SGC-000000999")))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v/SGC-000000999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolverVoucher_sinPdfGenerado_retorna404() throws Exception {
        Atencion sinPdf = new Atencion();
        sinPdf.setPdfUrl(null);
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000237"))
                .thenReturn(Optional.of(sinPdf));

        mockMvc.perform(get("/v/SGC-000000237"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolverVoucher_noRequiereAutenticacion() throws Exception {
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000236"))
                .thenReturn(Optional.of(atencionConPdf()));
        when(s3StorageService.generarUrlFirmada("prd/vouchers/2026/SGC-000000236.pdf"))
                .thenReturn("https://ejemplo-s3-firmada/voucher.pdf");

        mockMvc.perform(get("/v/SGC-000000236"))
                .andExpect(status().is3xxRedirection());
    }
}
