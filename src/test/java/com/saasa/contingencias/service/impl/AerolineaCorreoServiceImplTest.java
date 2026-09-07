package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.*;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.dto.request.AerolineaCorreoRequest;
import com.saasa.contingencias.domain.dto.response.AerolineaCorreoResponse;
import com.saasa.contingencias.domain.mapping.AerolineaCorreoMapper;
import com.saasa.contingencias.domain.model.AerolineaCorreo;
import com.saasa.contingencias.domain.model.EstacionLineaAerea;
import com.saasa.contingencias.domain.model.LineaAerea;
import com.saasa.contingencias.domain.repository.AerolineaCorreoRepository;
import com.saasa.contingencias.domain.repository.EstacionLineaAereaRepository;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * NUEVO — Cobertura para el cambio "multi-estación" de Correos de
 * Aerolíneas (ver AerolineaCorreoServiceImpl): el correo deja de ser un
 * registro global por nombre de aerolínea (texto libre) y pasa a
 * pertenecer siempre al contexto de trabajo activo (estación+línea
 * aérea), igual que ProveedorServiceImpl.
 *
 * OJO: no se usa @InjectMocks aquí a propósito. El constructor de
 * AerolineaCorreoServiceImpl ahora requiere EstacionContext,
 * LineaAereaRepository y EstacionLineaAereaRepository — si se agrega
 * una dependencia nueva al constructor sin declarar su @Mock, Mockito
 * la deja en null y el primer uso revienta con NullPointerException en
 * tiempo de ejecución (fue justo el bug que rompió
 * LineaAereaServiceImplTest la vez anterior). Con el constructor
 * explícito el compilador avisa en vez de fallar en runtime.
 */
@ExtendWith(MockitoExtension.class)
class AerolineaCorreoServiceImplTest {

    @Mock AerolineaCorreoRepository repository;
    @Mock AerolineaCorreoMapper mapper;
    @Mock EstacionContext estacionContext;
    @Mock LineaAereaRepository lineaAereaRepository;
    @Mock EstacionLineaAereaRepository estacionLineaAereaRepository;

    AerolineaCorreoServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AerolineaCorreoServiceImpl(
                repository, mapper, estacionContext, lineaAereaRepository, estacionLineaAereaRepository);

        lenient().when(estacionContext.resolverContextoActivo(any(), any()))
                .thenReturn(new ScopeEstacionLinea(1L, 1L));
        lenient().when(estacionContext.resolverContextoActivo())
                .thenReturn(new ScopeEstacionLinea(1L, 1L));
        lenient().when(estacionLineaAereaRepository.findByEstacionIdAndLineaAereaId(any(), any()))
                .thenReturn(Optional.of(EstacionLineaAerea.builder().estado(1).build()));
    }

    // ─── create ─────────────────────────────────────────────────────────

    @Test
    void create_exitoso_resuelveNombreDesdeElCatalogoYGuardaConContexto() {
        when(repository.existsByEstacionIdAndLineaAereaId(1L, 1L)).thenReturn(false);
        when(lineaAereaRepository.findById(1L))
                .thenReturn(Optional.of(LineaAerea.builder().id(1L).codigoIata("PU").nombre("Plus Ultra").build()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toResponse(any())).thenAnswer(inv -> {
            AerolineaCorreo a = inv.getArgument(0);
            return new AerolineaCorreoResponse(1L, a.getEstacionId(), a.getLineaAereaId(),
                    a.getAerolinea(), a.getCorreo(), a.getObservaciones(), a.getEstado(), null);
        });

        AerolineaCorreoRequest req = new AerolineaCorreoRequest(1L, null, "correo@plusultra.com", "obs");
        AerolineaCorreoResponse resp = service.create(req);

        assertEquals("Plus Ultra", resp.aerolinea());
        assertEquals(1L, resp.estacionId());
        assertEquals(1L, resp.lineaAereaId());

        ArgumentCaptor<AerolineaCorreo> captor = ArgumentCaptor.forClass(AerolineaCorreo.class);
        verify(repository).save(captor.capture());
        assertEquals(1L, captor.getValue().getEstacionId());
        assertEquals(1L, captor.getValue().getLineaAereaId());
        assertEquals("Plus Ultra", captor.getValue().getAerolinea());
    }

    @Test
    void create_yaExisteCorreoParaEsaEstacionYLinea_lanzaBadRequest() {
        when(repository.existsByEstacionIdAndLineaAereaId(1L, 1L)).thenReturn(true);

        AerolineaCorreoRequest req = new AerolineaCorreoRequest(1L, null, "correo@plusultra.com", null);
        assertThrows(BadRequestException.class, () -> service.create(req));

        verify(lineaAereaRepository, never()).findById(any());
        verify(repository, never()).save(any());
    }

    @Test
    void create_mismaAerolineaEnOtraEstacion_noChocaConElUniqueExistente() {
        when(estacionContext.resolverContextoActivo(any(), any()))
                .thenReturn(new ScopeEstacionLinea(2L, 1L));
        when(repository.existsByEstacionIdAndLineaAereaId(2L, 1L)).thenReturn(false);
        when(lineaAereaRepository.findById(1L))
                .thenReturn(Optional.of(LineaAerea.builder().id(1L).nombre("Plus Ultra").build()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toResponse(any())).thenAnswer(inv -> {
            AerolineaCorreo a = inv.getArgument(0);
            return new AerolineaCorreoResponse(2L, a.getEstacionId(), a.getLineaAereaId(),
                    a.getAerolinea(), a.getCorreo(), a.getObservaciones(), a.getEstado(), null);
        });

        AerolineaCorreoResponse resp = service.create(
                new AerolineaCorreoRequest(1L, 2L, "cuzco@plusultra.com", null));

        assertEquals(2L, resp.estacionId());
        verify(repository, never()).existsByEstacionIdAndLineaAereaId(1L, 1L);
    }

    @Test
    void create_lineaAereaNoHabilitadaEnLaEstacion_lanzaBadRequest() {
        when(estacionLineaAereaRepository.findByEstacionIdAndLineaAereaId(1L, 1L))
                .thenReturn(Optional.empty());

        AerolineaCorreoRequest req = new AerolineaCorreoRequest(1L, null, "correo@plusultra.com", null);
        assertThrows(BadRequestException.class, () -> service.create(req));

        verify(repository, never()).save(any());
    }

    @Test
    void create_lineaAereaInexistente_lanzaRecursoNoEncontrado() {
        when(repository.existsByEstacionIdAndLineaAereaId(1L, 1L)).thenReturn(false);
        when(lineaAereaRepository.findById(1L)).thenReturn(Optional.empty());

        AerolineaCorreoRequest req = new AerolineaCorreoRequest(1L, null, "correo@plusultra.com", null);
        assertThrows(RecursoNoEncontradoException.class, () -> service.create(req));
    }

    // ─── update ─────────────────────────────────────────────────────────

    @Test
    void update_actualizaCorreoYObservaciones_sinTocarEstacionNiLineaAerea() {
        AerolineaCorreo existente = AerolineaCorreo.builder()
                .id(5L).aerolinea("Plus Ultra").correo("viejo@x.com").estado(1).build();
        existente.setEstacionId(1L);
        existente.setLineaAereaId(1L);
        when(repository.findById(5L)).thenReturn(Optional.of(existente));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toResponse(any())).thenAnswer(inv -> {
            AerolineaCorreo a = inv.getArgument(0);
            return new AerolineaCorreoResponse(5L, a.getEstacionId(), a.getLineaAereaId(),
                    a.getAerolinea(), a.getCorreo(), a.getObservaciones(), a.getEstado(), null);
        });

        AerolineaCorreoRequest req = new AerolineaCorreoRequest(99L, 99L, "nuevo@plusultra.com", "obs nueva");
        AerolineaCorreoResponse resp = service.update(5L, req);

        assertEquals("nuevo@plusultra.com", resp.correo());
        assertEquals(1L, resp.estacionId());
        assertEquals(1L, resp.lineaAereaId());
        assertEquals("Plus Ultra", resp.aerolinea());
    }

    @Test
    void update_noExiste_lanzaRecursoNoEncontrado() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        AerolineaCorreoRequest req = new AerolineaCorreoRequest(1L, null, "x@x.com", null);
        assertThrows(RecursoNoEncontradoException.class, () -> service.update(99L, req));
    }

    @Test
    void update_registroDeOtraEstacion_lanzaAccesoDenegado() {
        AerolineaCorreo deOtraEstacion = AerolineaCorreo.builder().id(7L).aerolinea("LATAM").estado(1).build();
        deOtraEstacion.setEstacionId(2L);
        deOtraEstacion.setLineaAereaId(1L);
        when(repository.findById(7L)).thenReturn(Optional.of(deOtraEstacion));
        doThrow(new AccesoDenegadoException("No tiene acceso a este recurso"))
                .when(estacionContext).validarAccesoLectura(deOtraEstacion);

        AerolineaCorreoRequest req = new AerolineaCorreoRequest(1L, null, "x@x.com", null);
        assertThrows(AccesoDenegadoException.class, () -> service.update(7L, req));
        verify(repository, never()).save(any());
    }

    // ─── buscarCorreoPorContexto ────────────────────────────────────────

    @Test
    void buscarCorreoPorContexto_existeYActivo_retornaCorreo() {
        when(repository.findFirstByEstacionIdAndLineaAereaIdAndEstado(1L, 1L, 1))
                .thenReturn(Optional.of(AerolineaCorreo.builder().correo("ops@plusultra.com").estado(1).build()));

        assertEquals("ops@plusultra.com", service.buscarCorreoPorContexto(1L, 1L));
    }

    @Test
    void buscarCorreoPorContexto_mismaAerolineaOtraEstacion_noEncuentraElDeLaOtra() {
        when(repository.findFirstByEstacionIdAndLineaAereaIdAndEstado(1L, 1L, 1))
                .thenReturn(Optional.of(AerolineaCorreo.builder().correo("lima@latam.com").estado(1).build()));
        when(repository.findFirstByEstacionIdAndLineaAereaIdAndEstado(2L, 1L, 1))
                .thenReturn(Optional.of(AerolineaCorreo.builder().correo("cuzco@latam.com").estado(1).build()));

        assertEquals("lima@latam.com", service.buscarCorreoPorContexto(1L, 1L));
        assertEquals("cuzco@latam.com", service.buscarCorreoPorContexto(2L, 1L));
    }

    @Test
    void buscarCorreoPorContexto_estacionOLineaNula_retornaNullSinConsultarRepositorio() {
        assertNull(service.buscarCorreoPorContexto(null, 1L));
        assertNull(service.buscarCorreoPorContexto(1L, null));
        verifyNoInteractions(repository);
    }

    @Test
    void buscarCorreoPorContexto_noExiste_retornaNull() {
        when(repository.findFirstByEstacionIdAndLineaAereaIdAndEstado(1L, 1L, 1))
                .thenReturn(Optional.empty());
        assertNull(service.buscarCorreoPorContexto(1L, 1L));
    }
}
