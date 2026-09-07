package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.dto.request.AsignarLineaAereaRequest;
import com.saasa.contingencias.domain.dto.request.EstacionRequest;
import com.saasa.contingencias.domain.dto.response.EstacionLineaAereaResponse;
import com.saasa.contingencias.domain.dto.response.EstacionResponse;
import com.saasa.contingencias.domain.enumeration.EstadoVueloEnum;
import com.saasa.contingencias.domain.mapping.EstacionMapper;
import com.saasa.contingencias.domain.model.Estacion;
import com.saasa.contingencias.domain.model.EstacionLineaAerea;
import com.saasa.contingencias.domain.model.LineaAerea;
import com.saasa.contingencias.domain.repository.EstacionLineaAereaRepository;
import com.saasa.contingencias.domain.repository.EstacionRepository;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import com.saasa.contingencias.domain.repository.VueloRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EstacionServiceImplTest {

    @Mock EstacionRepository estacionRepository;
    @Mock LineaAereaRepository lineaAereaRepository;
    @Mock EstacionLineaAereaRepository estacionLineaAereaRepository;
    @Mock VueloRepository vueloRepository;
    @Mock EstacionMapper estacionMapper;
    @Mock com.saasa.contingencias.service.IS3StorageService s3StorageService;

    @InjectMocks EstacionServiceImpl estacionService;

    private Estacion lima;

    @BeforeEach
    void setUp() {
        lima = Estacion.builder().id(1L).codigoIata("LIM").nombre("Lima")
                .zonaHoraria("America/Lima").estado(1).build();
    }

    @Test
    void create_codigoDisponible_creaEstacion() {
        when(estacionRepository.existsByCodigoIata("CUZ")).thenReturn(false);
        when(estacionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(estacionMapper.toResponse(any())).thenReturn(
                new EstacionResponse(2L, "CUZ", "Cusco", "America/Lima", 1, null, null));

        EstacionResponse resp = estacionService.create(new EstacionRequest("cuz", "Cusco", null));

        assertEquals("CUZ", resp.codigoIata());
        verify(estacionRepository).save(argThat(e -> e.getCodigoIata().equals("CUZ")
                && e.getZonaHoraria().equals("America/Lima")));
    }

    @Test
    void create_codigoYaExiste_lanzaBadRequest() {
        when(estacionRepository.existsByCodigoIata("LIM")).thenReturn(true);
        assertThrows(BadRequestException.class,
                () -> estacionService.create(new EstacionRequest("lim", "Lima", null)));
    }

    @Test
    void findById_noExiste_lanzaRecursoNoEncontrado() {
        when(estacionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(RecursoNoEncontradoException.class, () -> estacionService.findById(99L));
    }

    @Test
    void changeEstado_desactivaSinBorrar() {
        when(estacionRepository.findById(1L)).thenReturn(Optional.of(lima));
        when(estacionRepository.save(any())).thenReturn(lima);

        estacionService.changeEstado(1L, 0);

        assertEquals(0, lima.getEstado());
        verify(estacionRepository).save(lima);
        verify(estacionRepository, never()).delete(any());
        verify(estacionRepository, never()).deleteById(any());
    }

    @Test
    void asignarLineaAerea_nueva_creaVinculo() {
        LineaAerea plusUltra = LineaAerea.builder().id(5L).codigoIata("PUL").nombre("Plus Ultra").estado(1).build();
        when(estacionRepository.findById(1L)).thenReturn(Optional.of(lima));
        when(lineaAereaRepository.findById(5L)).thenReturn(Optional.of(plusUltra));
        when(estacionLineaAereaRepository.findByEstacionIdAndLineaAereaId(1L, 5L)).thenReturn(Optional.empty());
        when(estacionLineaAereaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(estacionMapper.toRelacionResponse(any())).thenReturn(
                new EstacionLineaAereaResponse(1L, 5L, "PUL", "Plus Ultra", 1, 0L));

        EstacionLineaAereaResponse resp = estacionService.asignarLineaAerea(1L, new AsignarLineaAereaRequest(5L));

        assertEquals(5L, resp.lineaAereaId());
        verify(estacionLineaAereaRepository).save(argThat(rel ->
                rel.getEstacion().equals(lima) && rel.getLineaAerea().equals(plusUltra) && rel.getEstado() == 1));
    }

    @Test
    void asignarLineaAerea_lineaInexistente_lanzaRecursoNoEncontrado() {
        when(estacionRepository.findById(1L)).thenReturn(Optional.of(lima));
        when(lineaAereaRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(RecursoNoEncontradoException.class,
                () -> estacionService.asignarLineaAerea(1L, new AsignarLineaAereaRequest(999L)));
    }

    @Test
    void findLineasAereas_filtraPorEstado() {
        when(estacionRepository.findById(1L)).thenReturn(Optional.of(lima));

        LineaAerea plusUltra = LineaAerea.builder().id(5L).codigoIata("PUL").nombre("Plus Ultra").estado(1).build();

        EstacionLineaAerea activa = mock(EstacionLineaAerea.class);
        when(activa.getEstado()).thenReturn(1);
        when(activa.getLineaAerea()).thenReturn(plusUltra);

        EstacionLineaAerea inactiva = mock(EstacionLineaAerea.class);
        when(inactiva.getEstado()).thenReturn(0);

        when(estacionLineaAereaRepository.findByEstacionId(1L)).thenReturn(List.of(activa, inactiva));
        when(vueloRepository.countByEstacionIdAndLineaAereaIdAndEstado(1L, 5L, EstadoVueloEnum.ACTIVO))
                .thenReturn(3L);
        when(estacionMapper.toRelacionResponse(activa, 3L))
                .thenReturn(new EstacionLineaAereaResponse(1L, 5L, "PUL", "Plus Ultra", 1, 3L));

        List<EstacionLineaAereaResponse> resultado = estacionService.findLineasAereas(1L, 1);

        assertEquals(1, resultado.size());
        assertEquals(3L, resultado.get(0).totalVuelos());
    }

    @Test
    void cambiarEstadoLineaAerea_sinVinculoPrevio_lanzaRecursoNoEncontrado() {
        when(estacionLineaAereaRepository.findByEstacionIdAndLineaAereaId(1L, 5L)).thenReturn(Optional.empty());
        assertThrows(RecursoNoEncontradoException.class,
                () -> estacionService.cambiarEstadoLineaAerea(1L, 5L, 0));
    }
}
