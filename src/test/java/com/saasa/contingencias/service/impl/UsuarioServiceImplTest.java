package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.exception.AccesoDenegadoException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.domain.dto.request.AsignarEstacionRequest;
import com.saasa.contingencias.domain.dto.request.UsuarioRequest;
import com.saasa.contingencias.domain.dto.response.UsuarioResponse;
import com.saasa.contingencias.domain.enumeration.RolEnum;
import com.saasa.contingencias.domain.mapping.UsuarioMapper;
import com.saasa.contingencias.domain.model.Estacion;
import com.saasa.contingencias.domain.model.LineaAerea;
import com.saasa.contingencias.domain.model.Usuario;
import com.saasa.contingencias.domain.repository.EstacionLineaAereaRepository;
import com.saasa.contingencias.domain.repository.EstacionRepository;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import com.saasa.contingencias.domain.repository.UsuarioEstacionRepository;
import com.saasa.contingencias.domain.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsuarioServiceImplTest {

    @Mock UsuarioRepository usuarioRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock
    UsuarioMapper usuarioMapper;
    @Mock   EstacionContext estacionContext;
    @Mock UsuarioEstacionRepository usuarioEstacionRepository;
    @Mock EstacionRepository estacionRepository;
    @Mock LineaAereaRepository lineaAereaRepository;
    @Mock EstacionLineaAereaRepository estacionLineaAereaRepository;

    @InjectMocks UsuarioServiceImpl usuarioService;

    private Usuario usuarioExistente;
    private Usuario usuarioOtraEstacion;

    @BeforeEach
    void setUp() {
        usuarioExistente = Usuario.builder()
                .id(1L)
                .nombre("Ana")
                .apellido("Torres")
                .correo("ana@saasa.com")
                .codigoEmpleado("EMP001")
                .rol(RolEnum.AGENTE_SAASA)
                .passwordHash("hashed")
                .estado(1)
                .build();

        usuarioOtraEstacion = Usuario.builder()
                .id(2L)
                .nombre("Luis")
                .apellido("Ramos")
                .correo("luis@saasa.com")
                .codigoEmpleado("EMP002")
                .rol(RolEnum.AGENTE_SAASA)
                .passwordHash("hashed")
                .estado(1)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // create()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void create_correoYaExiste_lanzaBadRequestException() {
        UsuarioRequest req = new UsuarioRequest(
                "Ana", "Torres", "ana@saasa.com",
                "DNI123", "EMP002", RolEnum.AGENTE_SAASA, "pass123");
        when(usuarioRepository.existsByCorreo("ana@saasa.com")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> usuarioService.create(req));
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void create_codigoEmpleadoYaExiste_lanzaBadRequestException() {
        UsuarioRequest req = new UsuarioRequest(
                "Ana", "Torres", "nuevo@saasa.com",
                "DNI123", "EMP001", RolEnum.AGENTE_SAASA, "pass123");
        when(usuarioRepository.existsByCorreo("nuevo@saasa.com")).thenReturn(false);
        when(usuarioRepository.existsByCodigoEmpleado("EMP001")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> usuarioService.create(req));
    }

    @Test
    void create_sinPassword_lanzaBadRequestException() {
        UsuarioRequest req = new UsuarioRequest(
                "Ana", "Torres", "nuevo@saasa.com",
                "DNI123", "EMP002", RolEnum.AGENTE_SAASA, "");
        when(usuarioRepository.existsByCorreo(any())).thenReturn(false);
        when(usuarioRepository.existsByCodigoEmpleado(any())).thenReturn(false);

        assertThrows(BadRequestException.class, () -> usuarioService.create(req));
    }

    @Test
    void create_datosValidos_guardaYRetornaResponse() {
        UsuarioRequest req = new UsuarioRequest(
                "Ana", "Torres", "nuevo@saasa.com",
                "DNI123", "EMP002", RolEnum.AGENTE_SAASA, "pass123");
        when(usuarioRepository.existsByCorreo(any())).thenReturn(false);
        when(usuarioRepository.existsByCodigoEmpleado(any())).thenReturn(false);
        when(passwordEncoder.encode("pass123")).thenReturn("hashed");
        when(usuarioRepository.save(any())).thenReturn(usuarioExistente);

        when(usuarioMapper.toResponse(usuarioExistente)).thenReturn(
                new UsuarioResponse(1L, "Ana", "Torres", "ana@saasa.com",
                        "DNI001", "EMP001", "AGENTE_SAASA", 1, null));

        UsuarioResponse response = usuarioService.create(req);

        assertNotNull(response);
        verify(usuarioRepository).save(any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findAll() — RN-805 (Fase 4: filtro por estación del admin)
    //
    // El filtrado por estación+línea aérea ahora ocurre DENTRO de la query
    // (UsuarioSpecification#porContextoAdmin, un EXISTS contra
    // usuario_estacion), no en memoria después de paginar como antes. Con un
    // repositorio mockeado no se puede verificar el predicado SQL resultante,
    // así que estas pruebas verifican el contrato correcto a nivel de
    // servicio: Administrador Global nunca resuelve un contexto (no hay
    // restricción); un admin de estación sí lo resuelve, y lo que la
    // query (ya filtrada por el contexto, simulada aquí por el stub del
    // repositorio) devuelva se mapea y se retorna tal cual.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findAll_administradorGlobal_veUsuariosDeTodasLasEstaciones() {
        Pageable pageable = PageRequest.of(0, 10);
        when(estacionContext.esAdministradorGlobal()).thenReturn(true);
        when(usuarioRepository.findAll(nullable(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(usuarioExistente, usuarioOtraEstacion)));
        when(usuarioMapper.toResponse(usuarioExistente)).thenReturn(
                new UsuarioResponse(1L, "Ana", "Torres", "ana@saasa.com",
                        "DNI001", "EMP001", "AGENTE_SAASA", 1, null));
        when(usuarioMapper.toResponse(usuarioOtraEstacion)).thenReturn(
                new UsuarioResponse(2L, "Luis", "Ramos", "luis@saasa.com",
                        "DNI002", "EMP002", "AGENTE_SAASA", 1, null));

        var resultado = usuarioService.findAll(pageable);

        assertEquals(2, resultado.getTotalElements());
        verify(estacionContext, never()).resolverContextoActivoLectura();
    }

    @Test
    void findAll_adminDeEstacion_resuelveContextoActivoYRetornaLoQueDevuelveLaQuery() {
        // Simula que la BD ya aplicó el filtro por contexto (estación 10,
        // sin línea = todas las líneas): solo llega el usuario que comparte
        // esa estación — el otro usuario ya viene excluido por la query real.
        Pageable pageable = PageRequest.of(0, 10);
        when(estacionContext.esAdministradorGlobal()).thenReturn(false);
        when(estacionContext.resolverContextoActivoLectura())
                .thenReturn(new com.saasa.contingencias.config.security.ScopeEstacionLinea(10L, null));
        when(usuarioRepository.findAll(nullable(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(usuarioExistente)));
        when(usuarioMapper.toResponse(usuarioExistente)).thenReturn(
                new UsuarioResponse(1L, "Ana", "Torres", "ana@saasa.com",
                        "DNI001", "EMP001", "AGENTE_SAASA", 1, null));

        var resultado = usuarioService.findAll(pageable);

        assertEquals(1, resultado.getTotalElements());
        assertEquals(1L, resultado.getContent().get(0).id());
        verify(estacionContext).resolverContextoActivoLectura();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // buscar() — RN-805 (Fase 4: filtro por estación del admin)
    // Mismo criterio que findAll(): el filtrado real ahora vive en la
    // Specification (DB), estas pruebas verifican el contrato de servicio.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void buscar_administradorGlobal_noResuelveContextoYVeTodo() {
        Pageable pageable = PageRequest.of(0, 10);
        when(estacionContext.esAdministradorGlobal()).thenReturn(true);
        when(usuarioRepository.findAll(nullable(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(usuarioExistente, usuarioOtraEstacion)));
        when(usuarioMapper.toResponse(usuarioExistente)).thenReturn(
                new UsuarioResponse(1L, "Ana", "Torres", "ana@saasa.com",
                        "DNI001", "EMP001", "AGENTE_SAASA", 1, null));
        when(usuarioMapper.toResponse(usuarioOtraEstacion)).thenReturn(
                new UsuarioResponse(2L, "Luis", "Ramos", "luis@saasa.com",
                        "DNI002", "EMP002", "AGENTE_SAASA", 1, null));

        var resultado = usuarioService.buscar(null, null, null, null, null, null, pageable);

        assertEquals(2, resultado.getTotalElements());
        verify(estacionContext, never()).resolverContextoActivoLectura();
    }

    @Test
    void buscar_adminDeEstacionConVariasLineas_resuelveContextoDeLaLineaSeleccionada() {
        // Admin con Lima+PlusUltra y Lima+LATAM (varias líneas en una sola
        // estación): el contexto activo (seleccionado en el toolbar del
        // topbar) debe resolverse y usarse para filtrar — no basta con
        // "una sola estación" como antes.
        Pageable pageable = PageRequest.of(0, 10);
        when(estacionContext.esAdministradorGlobal()).thenReturn(false);
        when(estacionContext.resolverContextoActivoLectura())
                .thenReturn(new com.saasa.contingencias.config.security.ScopeEstacionLinea(10L, 5L));
        when(usuarioRepository.findAll(nullable(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(usuarioExistente)));
        when(usuarioMapper.toResponse(usuarioExistente)).thenReturn(
                new UsuarioResponse(1L, "Ana", "Torres", "ana@saasa.com",
                        "DNI001", "EMP001", "AGENTE_SAASA", 1, null));

        var resultado = usuarioService.buscar(null, null, null, null, null, null, pageable);

        assertEquals(1, resultado.getTotalElements());
        verify(estacionContext).resolverContextoActivoLectura();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // update()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void update_usuarioNoExistente_lanzaRecursoNoEncontradoException() {
        when(usuarioRepository.findById(99L)).thenReturn(Optional.empty());
        UsuarioRequest req = new UsuarioRequest(
                "Ana", "Torres", "ana@saasa.com",
                "DNI123", "EMP001", RolEnum.AGENTE_SAASA, null);

        assertThrows(RecursoNoEncontradoException.class,
                () -> usuarioService.update(99L, req));
    }

    @Test
    void update_usuarioExistente_actualizaNombreYRetornaResponse() {
        UsuarioRequest req = new UsuarioRequest(
                "AnaModificada", "Torres", "ana@saasa.com",
                "DNI123", "EMP001", RolEnum.AGENTE_SAASA, null);
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioExistente));
        when(usuarioRepository.save(any())).thenReturn(usuarioExistente);

        when(usuarioMapper.toResponse(usuarioExistente)).thenReturn(
                new UsuarioResponse(1L, "AnaModificada", "Torres", "ana@saasa.com",
                        "DNI123", "EMP001", "AGENTE_SAASA", 1, null));

        UsuarioResponse response = usuarioService.update(1L, req);

        assertNotNull(response);
        verify(usuarioRepository).save(usuarioExistente);
    }

    @Test
    void update_conNuevaPassword_encriptaAntesDePersistir() {
        UsuarioRequest req = new UsuarioRequest(
                "Ana", "Torres", "ana@saasa.com",
                "DNI123", "EMP001", RolEnum.AGENTE_SAASA, "nuevaPass");
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioExistente));
        when(passwordEncoder.encode("nuevaPass")).thenReturn("newHashed");
        when(usuarioRepository.save(any())).thenReturn(usuarioExistente);

        usuarioService.update(1L, req);

        verify(passwordEncoder).encode("nuevaPass");
        assertEquals("newHashed", usuarioExistente.getPasswordHash());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // changeEstado()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void changeEstado_usuarioExistente_actualizaEstado() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioExistente));
        when(usuarioRepository.save(any())).thenReturn(usuarioExistente);

        usuarioService.changeEstado(1L, 0);

        assertEquals(0, usuarioExistente.getEstado());
        verify(usuarioRepository).save(usuarioExistente);
    }

    @Test
    void changeEstado_usuarioNoExistente_lanzaRecursoNoEncontradoException() {
        when(usuarioRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class,
                () -> usuarioService.changeEstado(99L, 0));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getOrThrow()/validarScopeSobreUsuario() — RN-805 (Fase 4) + extensión de
    // línea aérea. Usado por update/changeEstado/findEstaciones/
    // asignarEstacion/quitarEstacion.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void update_usuarioObjetivoDeOtraEstacion_propagaAccesoDenegadoYNoGuarda() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioExistente));
        when(estacionContext.esAdministradorGlobal()).thenReturn(false);
        when(estacionContext.resolverContextoActivoLectura())
                .thenReturn(new com.saasa.contingencias.config.security.ScopeEstacionLinea(10L, null));
        Estacion cusco = Estacion.builder().id(20L).nombre("Cusco").codigoIata("CUZ").estado(1).build();
        when(usuarioEstacionRepository.findActivasByUsuarioId(1L)).thenReturn(
                List.of(com.saasa.contingencias.domain.model.UsuarioEstacion.builder()
                        .id(1L).usuario(usuarioExistente).estacion(cusco).estado(1).build()));
        UsuarioRequest req = new UsuarioRequest(
                "Ana", "Torres", "ana@saasa.com", "DNI123", "EMP001", RolEnum.AGENTE_SAASA, null);

        assertThrows(com.saasa.contingencias.config.exception.AccesoDenegadoException.class,
                () -> usuarioService.update(1L, req));
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void update_usuarioObjetivoComparteEstacionPeroOtraLinea_propagaAccesoDenegado() {
        // Admin activo en Lima+PlusUltra; el objetivo solo tiene Lima+LATAM
        // (misma estación, otra línea) — con la extensión de línea aérea ya
        // no alcanza con compartir estación.
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioExistente));
        when(estacionContext.esAdministradorGlobal()).thenReturn(false);
        when(estacionContext.resolverContextoActivoLectura())
                .thenReturn(new com.saasa.contingencias.config.security.ScopeEstacionLinea(10L, 5L));
        Estacion lima = Estacion.builder().id(10L).nombre("Lima").codigoIata("LIM").estado(1).build();
        LineaAerea latam = LineaAerea.builder().id(6L).nombre("LATAM").codigoIata("LAN").estado(1).build();
        when(usuarioEstacionRepository.findActivasByUsuarioId(1L)).thenReturn(
                List.of(com.saasa.contingencias.domain.model.UsuarioEstacion.builder()
                        .id(1L).usuario(usuarioExistente).estacion(lima).lineaAerea(latam).estado(1).build()));
        UsuarioRequest req = new UsuarioRequest(
                "Ana", "Torres", "ana@saasa.com", "DNI123", "EMP001", RolEnum.AGENTE_SAASA, null);

        assertThrows(com.saasa.contingencias.config.exception.AccesoDenegadoException.class,
                () -> usuarioService.update(1L, req));
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void update_usuarioObjetivoComparteEstacionYLinea_sePermite() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioExistente));
        when(usuarioRepository.save(any())).thenReturn(usuarioExistente);
        when(estacionContext.esAdministradorGlobal()).thenReturn(false);
        when(estacionContext.resolverContextoActivoLectura())
                .thenReturn(new com.saasa.contingencias.config.security.ScopeEstacionLinea(10L, 5L));
        Estacion lima = Estacion.builder().id(10L).nombre("Lima").codigoIata("LIM").estado(1).build();
        LineaAerea plusUltra = LineaAerea.builder().id(5L).nombre("Plus Ultra").codigoIata("PUL").estado(1).build();
        when(usuarioEstacionRepository.findActivasByUsuarioId(1L)).thenReturn(
                List.of(com.saasa.contingencias.domain.model.UsuarioEstacion.builder()
                        .id(1L).usuario(usuarioExistente).estacion(lima).lineaAerea(plusUltra).estado(1).build()));
        when(usuarioMapper.toResponse(any())).thenReturn(
                new UsuarioResponse(1L, "Ana", "Torres", "ana@saasa.com",
                        "DNI001", "EMP001", "AGENTE_SAASA", 1, null));
        UsuarioRequest req = new UsuarioRequest(
                "Ana", "Torres", "ana@saasa.com", "DNI123", "EMP001", RolEnum.AGENTE_SAASA, null);

        assertDoesNotThrow(() -> usuarioService.update(1L, req));
        verify(usuarioRepository).save(usuarioExistente);
    }

    @Test
    void update_objetivoSinRestriccionDeLineaEnLaMismaEstacion_sePermiteAunConLineaSeleccionadaDistinta() {
        // El usuario objetivo es, p. ej., Administrador de esa estación sin
        // línea fija (lineaAerea == null en su fila) — "todas las líneas"
        // siempre es compatible con cualquier línea que el admin tenga
        // seleccionada en el toolbar.
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioExistente));
        when(usuarioRepository.save(any())).thenReturn(usuarioExistente);
        when(estacionContext.esAdministradorGlobal()).thenReturn(false);
        when(estacionContext.resolverContextoActivoLectura())
                .thenReturn(new com.saasa.contingencias.config.security.ScopeEstacionLinea(10L, 5L));
        Estacion lima = Estacion.builder().id(10L).nombre("Lima").codigoIata("LIM").estado(1).build();
        when(usuarioEstacionRepository.findActivasByUsuarioId(1L)).thenReturn(
                List.of(com.saasa.contingencias.domain.model.UsuarioEstacion.builder()
                        .id(1L).usuario(usuarioExistente).estacion(lima).lineaAerea(null).estado(1).build()));
        when(usuarioMapper.toResponse(any())).thenReturn(
                new UsuarioResponse(1L, "Ana", "Torres", "ana@saasa.com",
                        "DNI001", "EMP001", "AGENTE_SAASA", 1, null));
        UsuarioRequest req = new UsuarioRequest(
                "Ana", "Torres", "ana@saasa.com", "DNI123", "EMP001", RolEnum.AGENTE_SAASA, null);

        assertDoesNotThrow(() -> usuarioService.update(1L, req));
        verify(usuarioRepository).save(usuarioExistente);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // asignarEstacion() — RN-805: un admin de estación no puede otorgar una
    // estación fuera de su propio alcance. Sin cobertura hasta ahora.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void asignarEstacion_fueraDelAlcanceDelAdmin_lanzaAccesoDenegado() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioExistente));
        when(estacionContext.estacionesActuales()).thenReturn(List.of(10L));
        Estacion trujillo = Estacion.builder().id(30L).nombre("Trujillo").codigoIata("TRU").estado(1).build();
        when(estacionRepository.findById(30L)).thenReturn(Optional.of(trujillo));
        var req = new com.saasa.contingencias.domain.dto.request.AsignarEstacionRequest(30L, null);

        assertThrows(com.saasa.contingencias.config.exception.AccesoDenegadoException.class,
                () -> usuarioService.asignarEstacion(1L, req));
        verify(usuarioEstacionRepository, never()).save(any());
    }

    @Test
    void asignarEstacion_administradorGlobal_puedeAsignarCualquierEstacion() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioExistente));
        when(estacionContext.estacionesActuales()).thenReturn(List.of()); // Admin Global
        Estacion trujillo = Estacion.builder().id(30L).nombre("Trujillo").codigoIata("TRU").estado(1).build();
        when(estacionRepository.findById(30L)).thenReturn(Optional.of(trujillo));
        when(usuarioEstacionRepository.findByUsuarioIdAndEstacionIdAndLineaAereaId(1L, 30L, null))
                .thenReturn(Optional.empty());
        when(usuarioEstacionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var req = new com.saasa.contingencias.domain.dto.request.AsignarEstacionRequest(30L, null);

        assertDoesNotThrow(() -> usuarioService.asignarEstacion(1L, req));
        verify(usuarioEstacionRepository).save(any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // asignarEstacion() con lineaAereaId — extensión estación+línea aérea.
    // Sin cobertura previa: es lógica completamente nueva.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void asignarEstacion_conLineaAereaHabilitada_guardaLaRelacionConLinea() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioExistente));
        when(estacionContext.estacionesActuales()).thenReturn(List.of()); // Admin Global
        Estacion lima = Estacion.builder().id(10L).nombre("Lima").codigoIata("LIM").estado(1).build();
        LineaAerea plusUltra = LineaAerea.builder().id(5L).nombre("Plus Ultra").codigoIata("PUL").estado(1).build();
        when(estacionRepository.findById(10L)).thenReturn(Optional.of(lima));
        when(lineaAereaRepository.findById(5L)).thenReturn(Optional.of(plusUltra));
        com.saasa.contingencias.domain.model.EstacionLineaAerea rel =
                com.saasa.contingencias.domain.model.EstacionLineaAerea.builder()
                        .estacion(lima).lineaAerea(plusUltra).estado(1).build();
        when(estacionLineaAereaRepository.findByEstacionIdAndLineaAereaId(10L, 5L))
                .thenReturn(Optional.of(rel));
        when(usuarioEstacionRepository.findByUsuarioIdAndEstacionIdAndLineaAereaId(1L, 10L, 5L))
                .thenReturn(Optional.empty());
        when(usuarioEstacionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var req = new com.saasa.contingencias.domain.dto.request.AsignarEstacionRequest(10L, 5L);

        var response = usuarioService.asignarEstacion(1L, req);

        assertEquals(5L, response.lineaAereaId());
        assertEquals("Plus Ultra", response.lineaAereaNombre());
        verify(usuarioEstacionRepository).save(any());
    }

    @Test
    void asignarEstacion_conLineaAereaNoHabilitadaEnLaEstacion_lanzaBadRequest() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioExistente));
        when(estacionContext.estacionesActuales()).thenReturn(List.of()); // Admin Global
        Estacion lima = Estacion.builder().id(10L).nombre("Lima").codigoIata("LIM").estado(1).build();
        LineaAerea latam = LineaAerea.builder().id(6L).nombre("LATAM").codigoIata("LAN").estado(1).build();
        when(estacionRepository.findById(10L)).thenReturn(Optional.of(lima));
        when(lineaAereaRepository.findById(6L)).thenReturn(Optional.of(latam));
        when(estacionLineaAereaRepository.findByEstacionIdAndLineaAereaId(10L, 6L))
                .thenReturn(Optional.empty()); // LATAM no está habilitada en Lima
        var req = new com.saasa.contingencias.domain.dto.request.AsignarEstacionRequest(10L, 6L);

        assertThrows(BadRequestException.class, () -> usuarioService.asignarEstacion(1L, req));
        verify(usuarioEstacionRepository, never()).save(any());
    }
}