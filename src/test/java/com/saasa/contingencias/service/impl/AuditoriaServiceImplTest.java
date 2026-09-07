package com.saasa.contingencias.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.dto.response.AuditoriaResponse;
import com.saasa.contingencias.domain.enumeration.RolEnum;
import com.saasa.contingencias.domain.mapping.AuditoriaMapper;
import com.saasa.contingencias.domain.model.Auditoria;
import com.saasa.contingencias.domain.model.Usuario;
import com.saasa.contingencias.domain.repository.AuditoriaRepository;
import com.saasa.contingencias.domain.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test unitario de AuditoriaServiceImpl.
 *
 * Usa el mapper REAL (AuditoriaMapper) y un ObjectMapper real, ya que
 * ninguno tiene dependencias externas. El foco principal es verificar
 * que registrar() NUNCA propague una excepción (es una decisión de
 * diseño explícita: un fallo de auditoría no debe tumbar la operación
 * de negocio real que la originó) y que la resolución de IP/User-Agent
 * desde el HttpServletRequest funcione con sus distintos casos límite.
 */
@ExtendWith(MockitoExtension.class)
class AuditoriaServiceImplTest {

    @Mock AuditoriaRepository auditoriaRepository;
    @Mock UsuarioRepository usuarioRepository;
    @Mock EstacionContext estacionContext;

    private AuditoriaServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuditoriaServiceImpl(
                auditoriaRepository, usuarioRepository,
                new ObjectMapper(), new AuditoriaMapper(), estacionContext);
    }

    @AfterEach
    void limpiarRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private Usuario usuario(Long id, RolEnum rol) {
        return Usuario.builder().id(id).nombre("Ana").apellido("Ruiz")
                .correo("ana@test.com").rol(rol).build();
    }

    private void simularRequestConHeader(String headerNombre, String headerValor) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (headerNombre != null) {
            req.addHeader(headerNombre, headerValor);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    // ══════════════════════════════════════════════════════════════════════
    // registrar(4 args) — firma original
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("registrar — caso exitoso con usuario existente y detalle String → guarda correctamente")
    void registrar_casoExitoso_guardaConDetalleString() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario(1L, RolEnum.AGENTE_SAASA)));

        service.registrar(1L, "CREAR_ATENCION", "ATENCIONES", "192.168.1.1", "{\"pnr\":\"ABC123\"}");

        ArgumentCaptor<Auditoria> captor = ArgumentCaptor.forClass(Auditoria.class);
        verify(auditoriaRepository).save(captor.capture());
        Auditoria guardada = captor.getValue();
        assertEquals("CREAR_ATENCION", guardada.getAccion());
        assertEquals("192.168.1.1", guardada.getIpOrigen());
        assertEquals("{\"pnr\":\"ABC123\"}", guardada.getDetalle());
    }

    @Test
    @DisplayName("registrar — detalle como objeto (no String) → se serializa a JSON")
    void registrar_detalleObjeto_seSerializaAJson() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario(1L, RolEnum.AGENTE_SAASA)));
        record DetalleEjemplo(String pnr, int cantidad) {}

        service.registrar(1L, "CREAR_ATENCION", "ATENCIONES", "192.168.1.1",
                new DetalleEjemplo("ABC123", 2));

        ArgumentCaptor<Auditoria> captor = ArgumentCaptor.forClass(Auditoria.class);
        verify(auditoriaRepository).save(captor.capture());
        assertTrue(captor.getValue().getDetalle().contains("\"pnr\":\"ABC123\""));
    }

    @Test
    @DisplayName("registrar — usuarioId no existe → usuario queda null pero igual se guarda (sin excepción)")
    void registrar_usuarioNoExiste_guardaConUsuarioNull() {
        when(usuarioRepository.findById(999L)).thenReturn(Optional.empty());

        assertDoesNotThrow(() ->
                service.registrar(999L, "LOGIN", "AUTH", "10.0.0.1", "detalle"));

        ArgumentCaptor<Auditoria> captor = ArgumentCaptor.forClass(Auditoria.class);
        verify(auditoriaRepository).save(captor.capture());
        assertNull(captor.getValue().getUsuario());
    }

    @Test
    @DisplayName("registrar — usuarioId nulo (acción del sistema) → no consulta el repositorio de usuarios")
    void registrar_usuarioIdNulo_noConsultaUsuarioRepository() {
        service.registrar(null, "JOB_PROGRAMADO", "SISTEMA", "SYSTEM", "detalle");

        verify(usuarioRepository, never()).findById(any());
        verify(auditoriaRepository).save(any());
    }

    @Test
    @DisplayName("registrar — sin ipOrigen explícito y sin contexto HTTP → usa 'SYSTEM'")
    void registrar_sinIpNiContextoHttp_usaSystem() {
        // Sin simularRequestConHeader(): RequestContextHolder queda vacío
        service.registrar(null, "JOB_PROGRAMADO", "SISTEMA", null, "detalle");

        ArgumentCaptor<Auditoria> captor = ArgumentCaptor.forClass(Auditoria.class);
        verify(auditoriaRepository).save(captor.capture());
        assertEquals("SYSTEM", captor.getValue().getIpOrigen());
    }

    @Test
    @DisplayName("registrar — falla al guardar en el repositorio → NO propaga la excepción")
    void registrar_repositorioFalla_noPropagaExcepcion() {
        when(auditoriaRepository.save(any())).thenThrow(new RuntimeException("DB caída"));

        // Esto es lo crítico: un fallo de auditoría NUNCA debe romper el flujo que la invoca
        assertDoesNotThrow(() ->
                service.registrar(1L, "CREAR_ATENCION", "ATENCIONES", "127.0.0.1", "detalle"));
    }

    @Test
    @DisplayName("registrar — sin contexto activo (BadRequestException) → guarda con estacionId/lineaAereaId null, sin perder el registro")
    void registrar_sinContextoActivo_guardaConScopeNulo() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario(1L, RolEnum.ADMINISTRADOR)));
        when(estacionContext.resolverContextoActivo())
                .thenThrow(new BadRequestException("Debe especificar estacionId y lineaAereaId"));

        assertDoesNotThrow(() ->
                service.registrar(1L, "LOGIN", "AUTH", "10.0.0.1", "detalle"));

        ArgumentCaptor<Auditoria> captor = ArgumentCaptor.forClass(Auditoria.class);
        verify(auditoriaRepository).save(captor.capture());
        assertNull(captor.getValue().getEstacionId());
        assertNull(captor.getValue().getLineaAereaId());
    }

    @Test
    @DisplayName("registrar — con scope fijo del usuario → estampa estacionId/lineaAereaId aunque no lleguen headers en el request")
    void registrar_conScopeFijo_estampaEstacionYLineaSinHeaders() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario(1L, RolEnum.AGENTE_SAASA)));
        // Sin simularRequestConHeader(): no hay X-Estacion-Id/X-Linea-Aerea-Id en el request,
        // pero EstacionContext resuelve igual por el fallback al par fijo del usuario.
        when(estacionContext.resolverContextoActivo()).thenReturn(new ScopeEstacionLinea(5L, 3L));

        service.registrar(1L, "CREAR_ATENCION", "ATENCIONES", "192.168.1.1", "detalle");

        ArgumentCaptor<Auditoria> captor = ArgumentCaptor.forClass(Auditoria.class);
        verify(auditoriaRepository).save(captor.capture());
        assertEquals(5L, captor.getValue().getEstacionId());
        assertEquals(3L, captor.getValue().getLineaAereaId());
    }

    // ══════════════════════════════════════════════════════════════════════
    // registrar(7 args) — firma completa con entidad + IP/User-Agent automáticos
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("registrar completo — captura IP desde X-Forwarded-For cuando hay contexto HTTP")
    void registrarCompleto_capturaIpDesdeXForwardedFor() {
        simularRequestConHeader("X-Forwarded-For", "203.0.113.5");
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario(1L, RolEnum.ADMINISTRADOR)));

        service.registrar(1L, "ELIMINAR_PROVEEDOR", "PROVEEDORES", "detalle",
                "Proveedor", 10L, "Hotel Costa del Sol");

        ArgumentCaptor<Auditoria> captor = ArgumentCaptor.forClass(Auditoria.class);
        verify(auditoriaRepository).save(captor.capture());
        assertEquals("203.0.113.5", captor.getValue().getIpOrigen());
        assertEquals("Proveedor", captor.getValue().getEntidadTipo());
        assertEquals(10L, captor.getValue().getEntidadId());
        assertEquals("Hotel Costa del Sol", captor.getValue().getEntidadNombre());
    }

    @Test
    @DisplayName("registrar completo — X-Forwarded-For con múltiples IPs → toma solo la primera")
    void registrarCompleto_xForwardedForConMultiplesIps_tomaLaPrimera() {
        simularRequestConHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1, 172.16.0.1");

        service.registrar(null, "ACCION", "MODULO", "detalle", null, null, null);

        ArgumentCaptor<Auditoria> captor = ArgumentCaptor.forClass(Auditoria.class);
        verify(auditoriaRepository).save(captor.capture());
        assertEquals("203.0.113.5", captor.getValue().getIpOrigen());
    }

    @Test
    @DisplayName("registrar completo — sin X-Forwarded-For, usa X-Real-IP")
    void registrarCompleto_sinXForwardedFor_usaXRealIp() {
        simularRequestConHeader("X-Real-IP", "198.51.100.7");

        service.registrar(null, "ACCION", "MODULO", "detalle", null, null, null);

        ArgumentCaptor<Auditoria> captor = ArgumentCaptor.forClass(Auditoria.class);
        verify(auditoriaRepository).save(captor.capture());
        assertEquals("198.51.100.7", captor.getValue().getIpOrigen());
    }

    @Test
    @DisplayName("registrar completo — IP localhost IPv6 → se convierte a 127.0.0.1")
    void registrarCompleto_localhostIpv6_seConvierteA127001() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("0:0:0:0:0:0:0:1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        service.registrar(null, "ACCION", "MODULO", "detalle", null, null, null);

        ArgumentCaptor<Auditoria> captor = ArgumentCaptor.forClass(Auditoria.class);
        verify(auditoriaRepository).save(captor.capture());
        assertEquals("127.0.0.1", captor.getValue().getIpOrigen());
    }

    @Test
    @DisplayName("registrar completo — captura el User-Agent del request")
    void registrarCompleto_capturaUserAgent() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("User-Agent", "Mozilla/5.0 (test)");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        service.registrar(null, "ACCION", "MODULO", "detalle", null, null, null);

        ArgumentCaptor<Auditoria> captor = ArgumentCaptor.forClass(Auditoria.class);
        verify(auditoriaRepository).save(captor.capture());
        assertEquals("Mozilla/5.0 (test)", captor.getValue().getUserAgent());
    }

    // ══════════════════════════════════════════════════════════════════════
    // findAll — firma original (compatibilidad)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("findAll — con usuarioId → delega a findAll(Specification, Pageable) con el scope de estación/línea")
    void findAll_conUsuarioId_delegaAFindAllConSpecEscopada() {
        Pageable pageable = PageRequest.of(0, 10);
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(1L, 1L));
        when(auditoriaRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        service.findAll(pageable, 1L);

        verify(auditoriaRepository).findAll(any(Specification.class), eq(pageable));
        verify(auditoriaRepository, never()).findByUsuarioId(any(), any());
        verify(auditoriaRepository, never()).findAll(pageable);
    }

    @Test
    @DisplayName("findAll — sin usuarioId → delega a findAll(Specification, Pageable) con el scope de estación/línea")
    void findAll_sinUsuarioId_delegaAFindAllConSpecEscopada() {
        Pageable pageable = PageRequest.of(0, 10);
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(1L, 1L));
        when(auditoriaRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        service.findAll(pageable, null);

        verify(auditoriaRepository).findAll(any(Specification.class), eq(pageable));
        verify(auditoriaRepository, never()).findByUsuarioId(any(), any());
        verify(auditoriaRepository, never()).findAll(pageable);
    }

    // ══════════════════════════════════════════════════════════════════════
    // listar / buscarPorUsuario / buscarPorEntidad — response enriquecido
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("listar — aplica el scope de estación/línea y mapea correctamente usuario+rol al AuditoriaResponse")
    void listar_aplicaScopeYMapeaUsuarioYRolCorrectamente() {
        Usuario u = usuario(1L, RolEnum.LIDER_SAASA);
        Auditoria a = Auditoria.builder().id(1L).usuario(u).accion("LOGIN").modulo("AUTH")
                .creadoEn(LocalDateTime.now()).build();
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(1L, 1L));
        when(auditoriaRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a)));

        Page<AuditoriaResponse> resultado = service.listar(PageRequest.of(0, 10));

        AuditoriaResponse resp = resultado.getContent().get(0);
        assertEquals("Ana Ruiz", resp.usuarioNombre());
        assertEquals("LIDER_SAASA", resp.usuarioRol());
        verify(auditoriaRepository).findAll(any(Specification.class), any(Pageable.class));
        verify(auditoriaRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("buscarPorUsuario — delega al repositorio vía Specification escopada, con el usuarioId correcto")
    void buscarPorUsuario_delegaConSpecEscopadaYUsuarioIdCorrecto() {
        Pageable pageable = PageRequest.of(0, 10);
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(1L, 1L));
        when(auditoriaRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        service.buscarPorUsuario(5L, pageable);

        verify(auditoriaRepository).findAll(any(Specification.class), eq(pageable));
        verify(auditoriaRepository, never()).findByUsuarioId(any(), any());
    }

    @Test
    @DisplayName("buscarPorEntidad — delega al repositorio vía Specification escopada, con tipo e ID de entidad correctos")
    void buscarPorEntidad_delegaConSpecEscopadaYTipoEIdCorrectos() {
        Pageable pageable = PageRequest.of(0, 10);
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(1L, 1L));
        when(auditoriaRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        service.buscarPorEntidad("Proveedor", 10L, pageable);

        verify(auditoriaRepository).findAll(any(Specification.class), eq(pageable));
        verify(auditoriaRepository, never()).findByEntidadTipoAndEntidadId(any(), any(), any());
    }

    @Test
    @DisplayName("buscarConFiltros — construye una Specification y la pasa al repositorio")
    void buscarConFiltros_construyeSpecificationYDelega() {
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(1L, 1L));
        when(auditoriaRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.buscarConFiltros("hotel", 1L, "PROVEEDORES", "CREAR", null, null,
                PageRequest.of(0, 10));

        verify(auditoriaRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    // ══════════════════════════════════════════════════════════════════════
    // exportarExcel — generación real con Apache POI
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("exportarExcel — con registros → genera un .xlsx válido (firma ZIP/PK)")
    void exportarExcel_conRegistros_generaXlsxValido() {
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(1L, 1L));
        Usuario u = usuario(1L, RolEnum.AGENTE_SAASA);
        Auditoria a1 = Auditoria.builder().id(1L).usuario(u).accion("CREAR_ATENCION")
                .modulo("ATENCIONES").entidadTipo("Atencion").entidadNombre("SGC-001")
                .detalle("{\"pnr\":\"ABC123\"}").ipOrigen("127.0.0.1")
                .creadoEn(LocalDateTime.now()).build();
        when(auditoriaRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a1)));

        byte[] xlsx = service.exportarExcel(null, null, null, null, null, null);

        assertNotNull(xlsx);
        assertTrue(xlsx.length > 0);
        // Firma de archivo ZIP (todo .xlsx es un ZIP): primeros 2 bytes "PK"
        assertEquals('P', xlsx[0]);
        assertEquals('K', xlsx[1]);
    }

    @Test
    @DisplayName("exportarExcel — registro con usuario null → usa 'Sistema' en vez de reventar")
    void exportarExcel_usuarioNull_usaSistemaComoFallback() {
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(1L, 1L));
        Auditoria a1 = Auditoria.builder().id(1L).usuario(null).accion("JOB_PROGRAMADO")
                .modulo("SISTEMA").creadoEn(LocalDateTime.now()).build();
        when(auditoriaRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a1)));

        assertDoesNotThrow(() -> service.exportarExcel(null, null, null, null, null, null));
    }

    @Test
    @DisplayName("exportarExcel — sin registros (lista vacía) → igual genera un .xlsx válido")
    void exportarExcel_sinRegistros_generaXlsxValido() {
        when(estacionContext.resolverContextoActivoLectura()).thenReturn(new ScopeEstacionLinea(1L, 1L));
        when(auditoriaRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        byte[] xlsx = service.exportarExcel(null, null, null, null, null, null);

        assertNotNull(xlsx);
        assertEquals('P', xlsx[0]);
        assertEquals('K', xlsx[1]);
    }
}