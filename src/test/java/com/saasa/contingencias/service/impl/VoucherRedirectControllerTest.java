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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
    void paginaVoucher_correlativoValido_devuelveHtmlConVisorYBotones() throws Exception {
        Atencion a = atencionConPdf();
        a.setNombre("Johnny MR");
        a.setApellido("Acero");
        a.setPnr("EJVRJR");
        when(atencionRepository.findByNumeroCorrelativo("SGC-LIM-LA-000002"))
                .thenReturn(Optional.of(a));

        mockMvc.perform(get("/v/SGC-LIM-LA-000002/ver"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("SGC-LIM-LA-000002")))
                .andExpect(content().string(containsString("Johnny MR Acero")))
                .andExpect(content().string(containsString("PNR: EJVRJR")))
                .andExpect(content().string(containsString("var urlPdf = '/v/SGC-LIM-LA-000002/pdf'")))
                .andExpect(content().string(containsString("pdf.min.js")))
                .andExpect(content().string(containsString("href=\"/v/SGC-LIM-LA-000002/pdf?descargar=true\"")))
                .andExpect(content().string(containsString("package=com.android.chrome")));
    }

    @Test
    void paginaVoucher_escapaHtml() throws Exception {
        Atencion a = atencionConPdf();
        a.setNombre("<script>");
        a.setApellido("X");
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000236"))
                .thenReturn(Optional.of(a));

        mockMvc.perform(get("/v/SGC-000000236/ver"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Pasajero: <script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    @Test
    void paginaVoucher_sinPdf_retorna404() throws Exception {
        Atencion sinPdf = new Atencion();
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000237"))
                .thenReturn(Optional.of(sinPdf));

        mockMvc.perform(get("/v/SGC-000000237/ver"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pdfVoucher_sirvePdfInline() throws Exception {
        byte[] pdf = "%PDF-1.4 prueba".getBytes();
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000236"))
                .thenReturn(Optional.of(atencionConPdf()));
        when(s3StorageService.descargarPdf("prd/vouchers/2026/SGC-000000236.pdf")).thenReturn(pdf);

        mockMvc.perform(get("/v/SGC-000000236/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("inline")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("SGC-000000236.pdf")))
                .andExpect(content().bytes(pdf));
    }

    @Test
    void pdfVoucher_descargar_enviaComoAdjunto() throws Exception {
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000236"))
                .thenReturn(Optional.of(atencionConPdf()));
        when(s3StorageService.descargarPdf("prd/vouchers/2026/SGC-000000236.pdf"))
                .thenReturn("%PDF-1.4".getBytes());

        mockMvc.perform(get("/v/SGC-000000236/pdf").param("descargar", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")));
    }

    @Test
    void pdfVoucher_sinPdf_retorna404() throws Exception {
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000999"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v/SGC-000000999/pdf"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pdfVoucher_errorS3_retorna500() throws Exception {
        when(atencionRepository.findByNumeroCorrelativo("SGC-000000236"))
                .thenReturn(Optional.of(atencionConPdf()));
        when(s3StorageService.descargarPdf("prd/vouchers/2026/SGC-000000236.pdf"))
                .thenThrow(new RuntimeException("S3 caído"));

        mockMvc.perform(get("/v/SGC-000000236/pdf"))
                .andExpect(status().isInternalServerError());
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
