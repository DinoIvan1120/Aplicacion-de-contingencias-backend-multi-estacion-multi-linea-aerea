package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.*;
import com.saasa.contingencias.config.security.JwtUtil;
import com.saasa.contingencias.domain.dto.request.AuthRequest;
import com.saasa.contingencias.domain.dto.request.ForgotPasswordRequest;
import com.saasa.contingencias.domain.dto.request.LoginRequest;
import com.saasa.contingencias.domain.dto.request.ResetPasswordRequest;
import com.saasa.contingencias.domain.dto.request.UsuarioRequest;
import com.saasa.contingencias.domain.enumeration.RolEnum;
import com.saasa.contingencias.domain.model.CodigoVerificacion;
import com.saasa.contingencias.domain.model.Usuario;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.service.IEmailService;
import com.saasa.contingencias.util.DateTimeUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock UsuarioRepository usuarioRepository;
    @Mock CodigoVerificacionRepository codigoVerificacionRepository;
    @Mock UsuarioEstacionRepository usuarioEstacionRepository;
    @Mock JwtUtil jwtUtil;
    @Mock PasswordEncoder passwordEncoder;
    @Mock IEmailService emailService;
    @InjectMocks AuthServiceImpl authService;

    @AfterEach
    void limpiarSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void login_usuarioInexistente_lanzaBadRequest() {
        when(usuarioRepository.findByCorreo("x@test.com")).thenReturn(Optional.empty());
        assertThrows(BadRequestException.class, () -> authService.login(new AuthRequest("x@test.com", "Pass1234")));
    }

    @Test
    void login_usuarioInactivo_lanzaAccesoDenegado() {
        Usuario u = Usuario.builder().correo("u@test.com").passwordHash("hash").estado(0).build();
        when(usuarioRepository.findByCorreo("u@test.com")).thenReturn(Optional.of(u));
        assertThrows(AccesoDenegadoException.class, () -> authService.login(new AuthRequest("u@test.com", "Pass1234")));
    }

    @Test
    void login_passwordIncorrecta_lanzaBadRequest() {
        Usuario u = Usuario.builder().correo("u@test.com").passwordHash("hash").estado(1).build();
        when(usuarioRepository.findByCorreo("u@test.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("WrongPass1", "hash")).thenReturn(false);
        assertThrows(BadRequestException.class, () -> authService.login(new AuthRequest("u@test.com", "WrongPass1")));
    }

    @Test
    void login_exitoso_retornaToken() {
        var rol = com.saasa.contingencias.domain.enumeration.RolEnum.AGENTE_SAASA;
        Usuario u = Usuario.builder().correo("u@test.com").passwordHash("hash").estado(1)
                .nombre("Juan").apellido("P").rol(rol).build();
        when(usuarioRepository.findByCorreo("u@test.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("Pass1234", "hash")).thenReturn(true);
        when(jwtUtil.generateToken(eq("u@test.com"), eq("AGENTE_SAASA"), any())).thenReturn("jwt-token");
        var resp = authService.login(new AuthRequest("u@test.com", "Pass1234"));
        assertEquals("jwt-token", resp.token());
        assertEquals("AGENTE_SAASA", resp.rol());
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // LOGIN — Flujo nuevo por DNI (LoginRequest)
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    void login_loginRequest_porDni_exitoso_retornaToken() {
        Usuario u = agente().documento("12345678").estado(1).nombre("Ana").apellido("R").build();
        when(usuarioRepository.findByDocumento("12345678")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("Pass1234", "hash")).thenReturn(true);
        when(jwtUtil.generateToken(eq("12345678"), eq("AGENTE_SAASA"), any())).thenReturn("jwt-dni-token");

        var resp = authService.login(new LoginRequest(null, "12345678", "Pass1234"));

        assertEquals("jwt-dni-token", resp.token());
        assertEquals("AGENTE_SAASA", resp.rol());
    }

    @Test
    void login_loginRequest_porDni_rolNoAgente_lanzaAccesoDenegado() {
        Usuario admin = Usuario.builder()
                .correo("admin@test.com").documento("99999999")
                .passwordHash("hash").estado(1)
                .rol(RolEnum.ADMINISTRADOR).build();
        when(usuarioRepository.findByDocumento("99999999")).thenReturn(Optional.of(admin));
        // sin mock de passwordEncoder — el service nunca llega a esa línea

        assertThrows(AccesoDenegadoException.class,
                () -> authService.login(new LoginRequest(null, "99999999", "Pass1234")));
    }

    @Test
    void login_loginRequest_porDni_usuarioInexistente_lanzaBadRequest() {
        when(usuarioRepository.findByDocumento("00000000")).thenReturn(Optional.empty());
        assertThrows(BadRequestException.class,
                () -> authService.login(new LoginRequest(null, "00000000", "Pass1234")));
    }

    @Test
    void login_loginRequest_sinIdentificador_lanzaBadRequest() {
        assertThrows(BadRequestException.class,
                () -> authService.login(new LoginRequest(null, null, "Pass1234")));
    }

    @Test
    void login_loginRequest_correoTienePrioridadSobreDni() {
        // Si vienen ambos campos, se usa el correo (compatibilidad)
        Usuario u = agente().correo("u@test.com").estado(1).nombre("L").apellido("M").build();
        when(usuarioRepository.findByCorreo("u@test.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("Pass1234", "hash")).thenReturn(true);
        when(jwtUtil.generateToken(eq("u@test.com"), eq("AGENTE_SAASA"), any())).thenReturn("jwt-correo");

        var resp = authService.login(new LoginRequest("u@test.com", "12345678", "Pass1234"));

        assertEquals("jwt-correo", resp.token());
        // No debe haberse llamado findByDocumento
        verify(usuarioRepository, never()).findByDocumento(any());
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // REGISTER — control de acceso (primer admin vs. registro protegido)
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    void register_sistemaVacio_sinAutenticacion_creaPrimerAdministrador() {
        when(usuarioRepository.count()).thenReturn(0L);
        when(usuarioRepository.existsByCorreo("admin@test.com")).thenReturn(false);
        when(usuarioRepository.existsByCodigoEmpleado("EMP-001")).thenReturn(false);
        when(passwordEncoder.encode("Pass1234")).thenReturn("hash-admin");
        when(usuarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UsuarioRequest request = new UsuarioRequest(
                "Admin", "Root", "admin@test.com", "00000000", "EMP-001",
                RolEnum.ADMINISTRADOR, "Pass1234");

        assertDoesNotThrow(() -> authService.register(request));
        verify(usuarioRepository).save(any());
    }

    @Test
    void register_conUsuariosExistentes_sinAutenticacion_lanzaAccesoDenegado() {
        when(usuarioRepository.count()).thenReturn(1L);
        // Sin autenticación en el contexto (caso real: llamada anónima)

        UsuarioRequest request = new UsuarioRequest(
                "Intruso", "Malicioso", "intruso@test.com", "99999999", "EMP-999",
                RolEnum.ADMINISTRADOR, "Pass1234");

        assertThrows(AccesoDenegadoException.class, () -> authService.register(request));
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void register_conUsuariosExistentes_tokenSinRolAdmin_lanzaAccesoDenegado() {
        when(usuarioRepository.count()).thenReturn(1L);
        autenticarComo("ROLE_AGENTE_SAASA");

        UsuarioRequest request = new UsuarioRequest(
                "Intruso", "Malicioso", "intruso@test.com", "99999999", "EMP-999",
                RolEnum.ADMINISTRADOR, "Pass1234");

        assertThrows(AccesoDenegadoException.class, () -> authService.register(request));
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void register_conUsuariosExistentes_tokenAdmin_creaUsuario() {
        when(usuarioRepository.count()).thenReturn(1L);
        autenticarComo("ROLE_ADMINISTRADOR");
        when(usuarioRepository.existsByCorreo("nuevo@test.com")).thenReturn(false);
        when(usuarioRepository.existsByCodigoEmpleado("EMP-002")).thenReturn(false);
        when(passwordEncoder.encode("Pass1234")).thenReturn("hash-nuevo");
        when(usuarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UsuarioRequest request = new UsuarioRequest(
                "Nuevo", "Usuario", "nuevo@test.com", "11111111", "EMP-002",
                RolEnum.AGENTE_SAASA, "Pass1234");

        assertDoesNotThrow(() -> authService.register(request));
        verify(usuarioRepository).save(any());
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // FORGOT PASSWORD
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    void forgotPassword_porCorreo_usuarioExiste_enviaCodigo() {
        Usuario u = agente().correo("u@test.com").build();
        when(usuarioRepository.findByCorreo("u@test.com")).thenReturn(Optional.of(u));
        doNothing().when(codigoVerificacionRepository).invalidarCodigosPrevios(any());
        when(codigoVerificacionRepository.save(any())).thenReturn(null);

        authService.forgotPassword(new ForgotPasswordRequest("u@test.com", null));

        verify(emailService).enviarCodigoVerificacion(eq("u@test.com"), any(), any(), anyInt());
    }

    @Test
    void forgotPassword_porDni_conCorreo_enviaCodigo() {
        Usuario u = agente().documento("12345678").correo("agente@test.com").build();
        when(usuarioRepository.findByDocumento("12345678")).thenReturn(Optional.of(u));
        doNothing().when(codigoVerificacionRepository).invalidarCodigosPrevios(any());
        when(codigoVerificacionRepository.save(any())).thenReturn(null);

        authService.forgotPassword(new ForgotPasswordRequest(null, "12345678"));

        verify(emailService).enviarCodigoVerificacion(eq("agente@test.com"), any(), any(), anyInt());
    }

    @Test
    void forgotPassword_porDni_sinCorreo_noEnviaCodigo_noLanzaExcepcion() {
        // Agente sin correo → flujo A (admin resetea). Debe responder 200 sin enviar email.
        Usuario u = agente().documento("12345678").correo(null).build();
        when(usuarioRepository.findByDocumento("12345678")).thenReturn(Optional.of(u));

        assertDoesNotThrow(() ->
                authService.forgotPassword(new ForgotPasswordRequest(null, "12345678")));

        verify(emailService, never()).enviarCodigoVerificacion(any(), any(), any(), anyInt());
    }

    @Test
    void forgotPassword_sinIdentificador_lanzaBadRequest() {
        assertThrows(BadRequestException.class,
                () -> authService.forgotPassword(new ForgotPasswordRequest(null, null)));
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // AGENTE TIENE CORREO
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    void agentetieneCorreo_conCorreo_retornaTrue() {
        Usuario u = agente().documento("12345678").correo("a@test.com").build();
        when(usuarioRepository.findByDocumento("12345678")).thenReturn(Optional.of(u));
        assertTrue(authService.agentetieneCorreo("12345678"));
    }

    @Test
    void agentetieneCorreo_sinCorreo_retornaFalse() {
        Usuario u = agente().documento("12345678").correo(null).build();
        when(usuarioRepository.findByDocumento("12345678")).thenReturn(Optional.of(u));
        assertFalse(authService.agentetieneCorreo("12345678"));
    }

    @Test
    void agentetieneCorreo_dniInexistente_retornaFalse() {
        when(usuarioRepository.findByDocumento("00000000")).thenReturn(Optional.empty());
        assertFalse(authService.agentetieneCorreo("00000000"));
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // RESET PASSWORD
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    void resetPassword_porCorreo_exitoso() {
        Usuario u = agente().correo("u@test.com").build();
        CodigoVerificacion cv = codigoValido(u);

        when(usuarioRepository.findByCorreo("u@test.com")).thenReturn(Optional.of(u));
        when(codigoVerificacionRepository
                .findTopByUsuarioCorreoAndUsadoFalseOrderByCreadoEnDesc("u@test.com"))
                .thenReturn(Optional.of(cv));
        when(passwordEncoder.encode("NuevaClave1")).thenReturn("nuevo-hash");

        authService.resetPassword(new ResetPasswordRequest("u@test.com", null, "123456", "NuevaClave1"));

        assertTrue(cv.getUsado());
        assertEquals("nuevo-hash", u.getPasswordHash());
        verify(emailService).enviarConfirmacionReset("u@test.com", u.getNombre(),"NuevaClave1");
    }

    @Test
    void resetPassword_porDni_sinCorreo_exitoso_noEnviaEmail() {
        Usuario u = agente().documento("12345678").correo(null).build();
        CodigoVerificacion cv = codigoValido(u);

        when(usuarioRepository.findByDocumento("12345678")).thenReturn(Optional.of(u));
        when(codigoVerificacionRepository
                .findTopByUsuarioIdAndUsadoFalseOrderByCreadoEnDesc(u.getId()))
                .thenReturn(Optional.of(cv));
        when(passwordEncoder.encode("NuevaClave1")).thenReturn("nuevo-hash");

        authService.resetPassword(new ResetPasswordRequest(null, "12345678", "123456", "NuevaClave1"));

        assertTrue(cv.getUsado());
        assertEquals("nuevo-hash", u.getPasswordHash());
        verify(emailService, never()).enviarConfirmacionReset(any(), any(),any());
    }

    @Test
    void resetPassword_codigoExpirado_lanzaBadRequest() {
        Usuario u = agente().correo("u@test.com").build();
        CodigoVerificacion cv = CodigoVerificacion.builder()
                .usuario(u).codigo("123456").usado(false)
                .expiresAt(DateTimeUtil.ahoraEnLima().minusMinutes(1)) // expirado
                .build();

        when(usuarioRepository.findByCorreo("u@test.com")).thenReturn(Optional.of(u));
        when(codigoVerificacionRepository
                .findTopByUsuarioCorreoAndUsadoFalseOrderByCreadoEnDesc("u@test.com"))
                .thenReturn(Optional.of(cv));

        assertThrows(BadRequestException.class,
                () -> authService.resetPassword(
                        new ResetPasswordRequest("u@test.com", null, "123456", "NuevaClave1")));
    }

    @Test
    void resetPassword_codigoIncorrecto_lanzaBadRequest() {
        Usuario u = agente().correo("u@test.com").build();
        CodigoVerificacion cv = codigoValido(u);

        when(usuarioRepository.findByCorreo("u@test.com")).thenReturn(Optional.of(u));
        when(codigoVerificacionRepository
                .findTopByUsuarioCorreoAndUsadoFalseOrderByCreadoEnDesc("u@test.com"))
                .thenReturn(Optional.of(cv));

        assertThrows(BadRequestException.class,
                () -> authService.resetPassword(
                        new ResetPasswordRequest("u@test.com", null, "999999", "NuevaClave1")));
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Helpers de construcción de objetos
    // ══════════════════════════════════════════════════════════════════════════════

    /** Builder base para un AGENTE_SAASA activo con hash fijo. */
    private Usuario.UsuarioBuilder agente() {
        return Usuario.builder()
                .id(1L)
                .nombre("TestAgente").apellido("AP")
                .passwordHash("hash")
                .estado(1)
                .rol(RolEnum.AGENTE_SAASA);
    }

    /** Código de verificación válido (no expirado, no usado, código = "123456"). */
    private CodigoVerificacion codigoValido(Usuario u) {
        return CodigoVerificacion.builder()
                .usuario(u)
                .codigo("123456")
                .usado(false)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
    }

    /** Simula un usuario autenticado con el rol/authority dado en el SecurityContext. */
    private void autenticarComo(String authority) {
        var auth = new UsernamePasswordAuthenticationToken(
                "test-user", null, List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}