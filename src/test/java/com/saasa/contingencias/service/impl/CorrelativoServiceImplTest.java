package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.domain.model.Estacion;
import com.saasa.contingencias.domain.model.LineaAerea;
import com.saasa.contingencias.domain.repository.CorrelativoSequenceRepository;
import com.saasa.contingencias.domain.repository.EstacionRepository;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Test unitario para CorrelativoServiceImpl.
 * Verifica el formato y la delegación al repositorio, ahora con
 * contador propio por combinación estación+aerolínea.
 */
@ExtendWith(MockitoExtension.class)
class CorrelativoServiceImplTest {

    @Mock
    CorrelativoSequenceRepository correlativoRepository;
    @Mock
    EstacionRepository estacionRepository;
    @Mock
    LineaAereaRepository lineaAereaRepository;

    @InjectMocks
    CorrelativoServiceImpl correlativoService;

    private Estacion lima() {
        return Estacion.builder().id(1L).codigoIata("LIM").nombre("Lima").build();
    }

    private LineaAerea plusUltra() {
        return LineaAerea.builder().id(2L).codigoIata("PU").nombre("Plus Ultra").build();
    }

    private LineaAerea latam() {
        return LineaAerea.builder().id(3L).codigoIata("LA").nombre("LATAM").build();
    }

    @Test
    @DisplayName("generarCorrelativo() debe retornar formato SGC-{estacion}-{aerolinea}-NNNNNN")
    void generarCorrelativo_formatoCorrecto() {
        when(estacionRepository.findById(1L)).thenReturn(Optional.of(lima()));
        when(lineaAereaRepository.findById(2L)).thenReturn(Optional.of(plusUltra()));
        when(correlativoRepository.obtenerUltimoNumeroGenerado(1L, 2L)).thenReturn(1000L);

        String resultado = correlativoService.generarCorrelativo(1L, 2L);

        assertThat(resultado).isEqualTo("SGC-LIM-PU-001000");
        verify(correlativoRepository, times(1)).incrementarYObtener(1L, 2L);
    }

    @Test
    @DisplayName("Cada llamada debe hacer exactamente un UPSERT en la tabla secuencia")
    void generarCorrelativo_llamaRepositorioUnaVezPorLlamada() {
        when(estacionRepository.findById(1L)).thenReturn(Optional.of(lima()));
        when(lineaAereaRepository.findById(2L)).thenReturn(Optional.of(plusUltra()));
        when(correlativoRepository.obtenerUltimoNumeroGenerado(1L, 2L)).thenReturn(1L, 2L);

        correlativoService.generarCorrelativo(1L, 2L);
        correlativoService.generarCorrelativo(1L, 2L);

        verify(correlativoRepository, times(2)).incrementarYObtener(1L, 2L);
    }

    @Test
    @DisplayName("Dos aerolíneas distintas en la misma estación llevan contadores independientes (ambas reinician en 1)")
    void generarCorrelativo_combinacionesDistintas_contadoresIndependientes() {
        when(estacionRepository.findById(1L)).thenReturn(Optional.of(lima()));
        when(lineaAereaRepository.findById(2L)).thenReturn(Optional.of(plusUltra()));
        when(lineaAereaRepository.findById(3L)).thenReturn(Optional.of(latam()));
        when(correlativoRepository.obtenerUltimoNumeroGenerado(1L, 2L)).thenReturn(1L);
        when(correlativoRepository.obtenerUltimoNumeroGenerado(1L, 3L)).thenReturn(1L);

        String correlativoPlusUltra = correlativoService.generarCorrelativo(1L, 2L);
        String correlativoLatam = correlativoService.generarCorrelativo(1L, 3L);

        assertThat(correlativoPlusUltra).isEqualTo("SGC-LIM-PU-000001");
        assertThat(correlativoLatam).isEqualTo("SGC-LIM-LA-000001");
        verify(correlativoRepository).incrementarYObtener(1L, 2L);
        verify(correlativoRepository).incrementarYObtener(1L, 3L);
    }

    @Test
    @DisplayName("estacionId o lineaAereaId nulos lanzan IllegalArgumentException sin tocar el repositorio")
    void generarCorrelativo_sinEstacionOAerolinea_lanzaExcepcion() {
        assertThrows(IllegalArgumentException.class, () -> correlativoService.generarCorrelativo(null, 2L));
        assertThrows(IllegalArgumentException.class, () -> correlativoService.generarCorrelativo(1L, null));
        verifyNoInteractions(correlativoRepository);
    }
}