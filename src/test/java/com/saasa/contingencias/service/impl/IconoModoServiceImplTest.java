package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.domain.model.IconoModo;
import com.saasa.contingencias.domain.repository.IconoModoRepository;
import com.saasa.contingencias.service.IS3StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IconoModoServiceImplTest {

    @Mock IconoModoRepository iconoModoRepository;
    @Mock IS3StorageService s3StorageService;

    @InjectMocks IconoModoServiceImpl iconoModoService;

    private final byte[] bytes = "fake-image".getBytes();

    // ── subirIcono ──────────────────────────────────────────────────────

    @Test
    void subirIcono_sinRegistroPrevio_creaFilaYSubeAS3() {
        when(iconoModoRepository.findByClave("GESTIONAR")).thenReturn(Optional.empty());
        when(s3StorageService.subirObjeto(eq(bytes), eq("iconos-modo/GESTIONAR.png"), eq("image/png")))
                .thenReturn("dev/iconos-modo/GESTIONAR.png");
        when(s3StorageService.generarUrlFirmada("dev/iconos-modo/GESTIONAR.png"))
                .thenReturn("https://s3-firmada/gestionar.png");

        String url = iconoModoService.subirIcono("GESTIONAR", bytes, "image/png");

        assertEquals("https://s3-firmada/gestionar.png", url);

        ArgumentCaptor<IconoModo> captor = ArgumentCaptor.forClass(IconoModo.class);
        verify(iconoModoRepository).save(captor.capture());
        assertEquals("GESTIONAR", captor.getValue().getClave());
        assertEquals("dev/iconos-modo/GESTIONAR.png", captor.getValue().getIconoKey());
    }

    @Test
    void subirIcono_conRegistroExistente_actualizaLaKeyDelMismoRegistro() {
        IconoModo existente = IconoModo.builder().id(1L).clave("OPERAR").iconoKey("dev/iconos-modo/OPERAR.jpg").build();
        when(iconoModoRepository.findByClave("OPERAR")).thenReturn(Optional.of(existente));
        when(s3StorageService.subirObjeto(any(), eq("iconos-modo/OPERAR.jpg"), eq("image/jpeg")))
                .thenReturn("dev/iconos-modo/OPERAR.jpg");
        when(s3StorageService.generarUrlFirmada(any())).thenReturn("https://s3-firmada/operar.jpg");

        iconoModoService.subirIcono("OPERAR", bytes, "image/jpeg");

        // Debe reutilizar la MISMA fila (mismo id), no crear una segunda.
        ArgumentCaptor<IconoModo> captor = ArgumentCaptor.forClass(IconoModo.class);
        verify(iconoModoRepository).save(captor.capture());
        assertEquals(1L, captor.getValue().getId());
    }

    @Test
    void subirIcono_contentTypeDistintoDePng_usaExtensionJpg() {
        when(iconoModoRepository.findByClave("OPERAR")).thenReturn(Optional.empty());
        when(s3StorageService.subirObjeto(any(), any(), any())).thenReturn("dev/iconos-modo/OPERAR.jpg");
        when(s3StorageService.generarUrlFirmada(any())).thenReturn("url");

        iconoModoService.subirIcono("OPERAR", bytes, "image/jpeg");

        verify(s3StorageService).subirObjeto(eq(bytes), eq("iconos-modo/OPERAR.jpg"), eq("image/jpeg"));
    }

    @Test
    void subirIcono_claveInvalida_lanzaBadRequestYNoLlegaAS3() {
        assertThrows(BadRequestException.class,
                () -> iconoModoService.subirIcono("NO_EXISTE", bytes, "image/png"));
        verifyNoInteractions(s3StorageService);
        verify(iconoModoRepository, never()).save(any());
    }

    @Test
    void subirIcono_claveEnMinusculas_seNormalizaAMayusculas() {
        when(iconoModoRepository.findByClave("GESTIONAR")).thenReturn(Optional.empty());
        when(s3StorageService.subirObjeto(any(), eq("iconos-modo/GESTIONAR.png"), any()))
                .thenReturn("dev/iconos-modo/GESTIONAR.png");
        when(s3StorageService.generarUrlFirmada(any())).thenReturn("url");

        iconoModoService.subirIcono("gestionar", bytes, "image/png");

        verify(iconoModoRepository).findByClave("GESTIONAR");
    }

    // ── obtenerUrlIcono ─────────────────────────────────────────────────

    @Test
    void obtenerUrlIcono_sinIconoSubido_retornaNullSinLlamarAS3() {
        when(iconoModoRepository.findByClave("GESTIONAR")).thenReturn(Optional.empty());

        String url = iconoModoService.obtenerUrlIcono("GESTIONAR");

        assertNull(url);
        verifyNoInteractions(s3StorageService);
    }

    @Test
    void obtenerUrlIcono_registroExisteConKeyEnBlanco_retornaNull() {
        IconoModo sinIcono = IconoModo.builder().id(1L).clave("OPERAR").iconoKey("").build();
        when(iconoModoRepository.findByClave("OPERAR")).thenReturn(Optional.of(sinIcono));

        assertNull(iconoModoService.obtenerUrlIcono("OPERAR"));
        verifyNoInteractions(s3StorageService);
    }

    @Test
    void obtenerUrlIcono_conIconoSubido_retornaUrlFirmadaDeEsaKey() {
        IconoModo conIcono = IconoModo.builder().id(1L).clave("OPERAR").iconoKey("dev/iconos-modo/OPERAR.jpg").build();
        when(iconoModoRepository.findByClave("OPERAR")).thenReturn(Optional.of(conIcono));
        when(s3StorageService.generarUrlFirmada("dev/iconos-modo/OPERAR.jpg")).thenReturn("https://s3-firmada/operar.jpg");

        String url = iconoModoService.obtenerUrlIcono("OPERAR");

        assertEquals("https://s3-firmada/operar.jpg", url);
    }

    @Test
    void obtenerUrlIcono_claveInvalida_lanzaBadRequest() {
        assertThrows(BadRequestException.class, () -> iconoModoService.obtenerUrlIcono("INVALIDA"));
        verifyNoInteractions(iconoModoRepository, s3StorageService);
    }
}