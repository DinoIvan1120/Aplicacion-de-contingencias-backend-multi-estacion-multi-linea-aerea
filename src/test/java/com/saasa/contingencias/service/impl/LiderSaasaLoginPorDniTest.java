package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.AccesoDenegadoException;
import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.config.security.JwtUtil;
import com.saasa.contingencias.domain.dto.request.LoginRequest;
import com.saasa.contingencias.domain.dto.request.UsuarioRequest;
import com.saasa.contingencias.domain.enumeration.RolEnum;
import com.saasa.contingencias.domain.mapping.UsuarioMapper;
import com.saasa.contingencias.domain.model.Usuario;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.service.IEmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * LIDER_SAASA con correo opcional e ingreso por DNI (igual que AGENTE_SAASA),
 * conservando su rol LIDER_SAASA en el token.
 */
@ExtendWith(MockitoExtension.class)
class LiderSaasaLoginPorDniTest {

    @Mock UsuarioRepository usuarioRepository;
    @Mock CodigoVerificacionRepository codigoVerificacionRepository;
    @Mock UsuarioEstacionRepository usuarioEstacionRepository;
    @Mock EstacionRepository estacionRepository;
    @Mock LineaAereaRepository lineaAereaRepository;
    @Mock EstacionLineaAereaRepository estacionLineaAereaRepository;
    @Mock EstacionContext estacionContext;
    @Mock UsuarioMapper usuarioMapper;
    @Mock JwtUtil jwtUtil;
    @Mock PasswordEncoder passwordEncoder;
    @Mock IEmailService emailService;

    @InjectMocks AuthServiceImpl authService;
    @InjectMocks UsuarioServiceImpl usuarioService;

    @AfterEach
    void limpiarSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─── RolEnum ────────────────────────────────────────────────────────────

    @Test
    void rolEnum_soloAgenteYLiderPermitenLoginPorDni() {
        assertTrue(RolEnum.AGENTE_SAASA.permiteLoginPorDni());
        assertTrue(RolEnum.LIDER_SAASA.permiteLoginPorDni());
        assertFalse(RolEnum.ADMINISTRADOR.permiteLoginPorDni());
        assertFalse(RolEnum.LINEA_AEREA.permiteLoginPorDni());
        assertFalse(RolEnum.PROVEEDOR.permiteLoginPorDni());
    }

    // ─── Login ──────────────────────────────────────────────────────────────

    @Test
    void login_liderSinCorreo_porDni_retornaTokenConRolLider() {
        Usuario lider = lider().documento("44556677").build(); // sin correo
        when(usuarioRepository.findByDocumento("44556677")).thenReturn(Optional.of(lider));
        when(passwordEncoder.matches("Pass1234", "hash")).thenReturn(true);
        when(jwtUtil.generateToken(eq("44556677"), eq("LIDER_SAASA"), any())).thenReturn("jwt-lider-dni");

        var resp = authService.login(new LoginRequest(null, "44556677", "Pass1234"));

        assertEquals("jwt-lider-dni", resp.token());
        assertEquals("LIDER_SAASA", resp.rol());
    }

    @Test
    void login_liderConCorreo_porDni_subjectSigueSiendoElCorreo() {
        Usuario lider = lider().correo("lider@saasa.com").documento("44556677").build();
        when(usuarioRepository.findByDocumento("44556677")).thenReturn(Optional.of(lider));
        when(passwordEncoder.matches("Pass1234", "hash")).thenReturn(true);
        when(jwtUtil.generateToken(eq("lider@saasa.com"), eq("LIDER_SAASA"), any())).thenReturn("jwt-lider");

        var resp = authService.login(new LoginRequest(null, "44556677", "Pass1234"));

        assertEquals("jwt-lider", resp.token());
        assertEquals("LIDER_SAASA", resp.rol());
    }

    @Test
    void login_proveedorPorDni_sigueDenegado() {
        Usuario prov = Usuario.builder().id(9L).correo("p@x.com").documento("20123456789")
                .passwordHash("hash").estado(1).rol(RolEnum.PROVEEDOR).build();
        when(usuarioRepository.findByDocumento("20123456789")).thenReturn(Optional.of(prov));

        assertThrows(AccesoDenegadoException.class,
                () -> authService.login(new LoginRequest(null, "20123456789", "Pass1234")));
    }

    // ─── Registro (AuthServiceImpl.register) ────────────────────────────────

    @Test
    void register_liderSinCorreo_guardaCorreoNull() {
        when(usuarioRepository.count()).thenReturn(1L);
        autenticarComo("ROLE_ADMINISTRADOR");
        when(usuarioRepository.existsByDocumento("44556677")).thenReturn(false);
        when(usuarioRepository.existsByCodigoEmpleado("EMP-L01")).thenReturn(false);
        when(passwordEncoder.encode("Pass1234")).thenReturn("hash");
        when(usuarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        authService.register(new UsuarioRequest(
                "Luis", "Lider", "  ", "44556677", "EMP-L01", RolEnum.LIDER_SAASA, "Pass1234"));

        ArgumentCaptor<Usuario> captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(captor.capture());
        assertNull(captor.getValue().getCorreo());
        assertEquals(RolEnum.LIDER_SAASA, captor.getValue().getRol());
    }

    @Test
    void register_administradorSinCorreo_lanzaBadRequest() {
        when(usuarioRepository.count()).thenReturn(1L);
        autenticarComo("ROLE_ADMINISTRADOR");

        assertThrows(BadRequestException.class, () -> authService.register(new UsuarioRequest(
                "Ad", "Min", null, "11112222", "EMP-A01", RolEnum.ADMINISTRADOR, "Pass1234")));
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void register_liderConDniDuplicado_lanzaBadRequest() {
        when(usuarioRepository.count()).thenReturn(1L);
        autenticarComo("ROLE_ADMINISTRADOR");
        when(usuarioRepository.existsByDocumento("44556677")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> authService.register(new UsuarioRequest(
                "Luis", "Lider", null, "44556677", "EMP-L01", RolEnum.LIDER_SAASA, "Pass1234")));
        verify(usuarioRepository, never()).save(any());
    }

    // ─── Actualización (UsuarioServiceImpl.update) ──────────────────────────

    @Test
    void update_liderQuitandoCorreo_permitidoYConservaRol() {
        Usuario existente = lider().correo("old@saasa.com").documento("44556677")
                .codigoEmpleado("EMP-L01").build();
        when(usuarioRepository.findById(2L)).thenReturn(Optional.of(existente));
        when(estacionContext.esAdministradorGlobal()).thenReturn(true);
        when(usuarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        usuarioService.update(2L, new UsuarioRequest(
                "Luis", "Lider", "", "44556677", "EMP-L01", RolEnum.LIDER_SAASA, null));

        ArgumentCaptor<Usuario> captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(captor.capture());
        assertNull(captor.getValue().getCorreo());
        assertEquals(RolEnum.LIDER_SAASA, captor.getValue().getRol());
    }

    @Test
    void update_administradorSinCorreo_lanzaBadRequest() {
        Usuario existente = lider().rol(RolEnum.ADMINISTRADOR).correo("adm@saasa.com")
                .documento("11112222").build();
        when(usuarioRepository.findById(2L)).thenReturn(Optional.of(existente));
        when(estacionContext.esAdministradorGlobal()).thenReturn(true);

        assertThrows(BadRequestException.class, () -> usuarioService.update(2L, new UsuarioRequest(
                "Ad", "Min", null, "11112222", "EMP-A01", RolEnum.ADMINISTRADOR, null)));
        verify(usuarioRepository, never()).save(any());
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private Usuario.UsuarioBuilder lider() {
        return Usuario.builder()
                .id(2L)
                .nombre("Luis").apellido("Lider")
                .passwordHash("hash")
                .estado(1)
                .rol(RolEnum.LIDER_SAASA);
    }

    private void autenticarComo(String authority) {
        var auth = new UsernamePasswordAuthenticationToken(
                "test-user", null, List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}