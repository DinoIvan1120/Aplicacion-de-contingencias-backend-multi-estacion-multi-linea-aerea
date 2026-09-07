package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.dto.request.LineaAereaRequest;
import com.saasa.contingencias.domain.dto.response.LineaAereaResponse;
import com.saasa.contingencias.domain.mapping.LineaAereaMapper;
import com.saasa.contingencias.domain.model.LineaAerea;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import com.saasa.contingencias.service.IPdfGeneratorService;
import com.saasa.contingencias.service.IS3StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LineaAereaServiceImplTest {

    @Mock LineaAereaRepository lineaAereaRepository;
    @Mock LineaAereaMapper lineaAereaMapper;
    // Explícito aunque estos tests no lo usen directamente: el constructor
// de LineaAereaServiceImpl ahora lo requiere (subirLogo/obtenerUrlLogo).
    @Mock IS3StorageService s3StorageService;

    // NUEVO — subirLogo() invalida el caché de PdfGeneratorServiceImpl
    // para que el próximo voucher no sirva el logo viejo desde memoria.
    @Mock IPdfGeneratorService pdfGeneratorService;

    @InjectMocks LineaAereaServiceImpl lineaAereaService;

    private LineaAerea plusUltra;

    @BeforeEach
    void setUp() {
        plusUltra = LineaAerea.builder().id(1L).codigoIata("PUL").nombre("Plus Ultra").estado(1).build();
    }

    @Test
    void create_codigoDisponible_creaLinea() {
        when(lineaAereaRepository.existsByCodigoIata("LA")).thenReturn(false);
        when(lineaAereaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineaAereaMapper.toResponse(any())).thenReturn(new LineaAereaResponse(2L, "LA", "LATAM", 1, null, null));

        LineaAereaResponse resp = lineaAereaService.create(new LineaAereaRequest("la", "LATAM"));

        assertEquals("LA", resp.codigoIata());
        verify(lineaAereaRepository).save(argThat(l -> l.getCodigoIata().equals("LA") && l.getNombre().equals("LATAM")));
    }

    @Test
    void create_codigoYaExiste_lanzaBadRequest() {
        when(lineaAereaRepository.existsByCodigoIata("PUL")).thenReturn(true);
        assertThrows(BadRequestException.class,
                () -> lineaAereaService.create(new LineaAereaRequest("pul", "Plus Ultra")));
    }

    @Test
    void update_noCambiaCodigo_noValidaUnicidad() {
        when(lineaAereaRepository.findById(1L)).thenReturn(Optional.of(plusUltra));
        when(lineaAereaRepository.save(any())).thenReturn(plusUltra);
        when(lineaAereaMapper.toResponse(any())).thenReturn(new LineaAereaResponse(1L, "PUL", "Plus Ultra S.A.", 1, null, null));

        lineaAereaService.update(1L, new LineaAereaRequest("PUL", "Plus Ultra S.A."));

        verify(lineaAereaRepository, never()).existsByCodigoIata(any());
        assertEquals("Plus Ultra S.A.", plusUltra.getNombre());
    }

    @Test
    void update_cambiaCodigoAUnoExistente_lanzaBadRequest() {
        when(lineaAereaRepository.findById(1L)).thenReturn(Optional.of(plusUltra));
        when(lineaAereaRepository.existsByCodigoIata("LA")).thenReturn(true);

        assertThrows(BadRequestException.class,
                () -> lineaAereaService.update(1L, new LineaAereaRequest("la", "LATAM")));
    }

    @Test
    void changeEstado_noExiste_lanzaRecursoNoEncontrado() {
        when(lineaAereaRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(RecursoNoEncontradoException.class, () -> lineaAereaService.changeEstado(99L, 0));
    }

    // ══════════════════════════════════════════════════════════════════════
    // subirLogo / obtenerUrlLogo
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void subirLogo_exitoso_guardaExactamenteLaKeyQueDevuelveS3() {
        // Este es el caso que cubre el bug real que se corrigió: la key que
        // queda en logoKey debe ser SIEMPRE la que devuelve subirObjeto
        // (que ya incluye el prefijo de ambiente), nunca una reconstruida
        // a mano en el service — si no, generarUrlFirmada() apunta a un
        // objeto que no existe.
        byte[] logoBytes = "logo-bytes".getBytes();
        when(lineaAereaRepository.findById(1L)).thenReturn(Optional.of(plusUltra));
        when(s3StorageService.subirObjeto(eq(logoBytes), eq("logos/PUL.png"), eq("image/png")))
                .thenReturn("contingencias/logos/PUL.png");
        when(lineaAereaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineaAereaMapper.toResponse(any()))
                .thenReturn(new LineaAereaResponse(1L, "PUL", "Plus Ultra", 1, "contingencias/logos/PUL.png", null));

        LineaAereaResponse resp = lineaAereaService.subirLogo(1L, logoBytes, "image/png");

        assertEquals("contingencias/logos/PUL.png", resp.logoKey());
        assertEquals("contingencias/logos/PUL.png", plusUltra.getLogoKey());
        verify(lineaAereaRepository).save(plusUltra);
    }

    @Test
    void subirLogo_exitoso_invalidaElCacheDelLogoEnPdfGeneratorService() {
        // Cubre el bug reportado: al reemplazar el logo, la key en S3 es
        // siempre la misma (logos/{codigoIata}.png), así que si no se
        // invalida el caché en memoria de PdfGeneratorServiceImpl, los
        // vouchers siguen mostrando el logo anterior indefinidamente.
        byte[] logoBytes = "logo-bytes-nuevo".getBytes();
        when(lineaAereaRepository.findById(1L)).thenReturn(Optional.of(plusUltra));
        when(s3StorageService.subirObjeto(eq(logoBytes), eq("logos/PUL.png"), eq("image/png")))
                .thenReturn("contingencias/logos/PUL.png");
        when(lineaAereaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineaAereaMapper.toResponse(any()))
                .thenReturn(new LineaAereaResponse(1L, "PUL", "Plus Ultra", 1, "contingencias/logos/PUL.png", null));

        lineaAereaService.subirLogo(1L, logoBytes, "image/png");

        verify(pdfGeneratorService).invalidarCacheLogo(1L);
    }

    @Test
    void subirLogo_noExiste_lanzaRecursoNoEncontrado() {
        when(lineaAereaRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(RecursoNoEncontradoException.class,
                () -> lineaAereaService.subirLogo(99L, "x".getBytes(), "image/png"));
        verifyNoInteractions(s3StorageService);
        verifyNoInteractions(pdfGeneratorService);
    }

    @Test
    void obtenerUrlLogo_conLogoKey_delegaEnS3YRetornaUrlFirmada() {
        plusUltra.setLogoKey("contingencias/logos/PUL.png");
        when(lineaAereaRepository.findById(1L)).thenReturn(Optional.of(plusUltra));
        when(s3StorageService.generarUrlFirmada("contingencias/logos/PUL.png"))
                .thenReturn("https://s3.amazonaws.com/bucket/contingencias/logos/PUL.png?signature=abc");

        String url = lineaAereaService.obtenerUrlLogo(1L);

        assertEquals("https://s3.amazonaws.com/bucket/contingencias/logos/PUL.png?signature=abc", url);
    }

    @Test
    void obtenerUrlLogo_sinLogoKey_retornaNullSinLlamarAS3() {
        // plusUltra no tiene logoKey seteado (queda null por defecto)
        when(lineaAereaRepository.findById(1L)).thenReturn(Optional.of(plusUltra));

        String url = lineaAereaService.obtenerUrlLogo(1L);

        assertNull(url);
        verifyNoInteractions(s3StorageService);
    }
}
