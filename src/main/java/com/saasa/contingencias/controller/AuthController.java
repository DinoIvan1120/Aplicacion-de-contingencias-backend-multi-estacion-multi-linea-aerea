package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.dto.request.*;
import com.saasa.contingencias.domain.dto.response.AuthResponse;
import com.saasa.contingencias.domain.dto.response.UsuarioResponse;;
import com.saasa.contingencias.service.IAuthService;
import com.saasa.contingencias.util.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticación")
public class AuthController {

    private final IAuthService authService;

    public AuthController(IAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(summary = "Login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.login(request)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @RequestHeader("Authorization") String bearerToken) {
        String token = bearerToken.replace("Bearer ", "");
        return ResponseEntity.ok(ApiResponse.success(authService.refresh(token)));
    }

    /**
     * Registro público cuando no hay usuarios en el sistema (primer ADMINISTRADOR).
     * A partir del segundo usuario, el service verifica que el caller sea ADMINISTRADOR.
     * Toda la lógica de autorización reside en el service (SRP).
     */
    @PostMapping("/register")
    @Operation(summary = """
        Registra un nuevo usuario con el rol especificado.
        - Sin usuarios en el sistema: público (primer ADMINISTRADOR).
        - Con usuarios existentes: requiere token con rol ADMINISTRADOR.
        Roles disponibles: ADMINISTRADOR, LIDER_SAASA, AGENTE_SAASA, LINEA_AEREA, PROVEEDOR
        """)
    public ResponseEntity<ApiResponse<UsuarioResponse>> register(
            @Valid @RequestBody UsuarioRequest request) {
        UsuarioResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "Usuario registrado exitosamente con rol " + request.rol().name(), response));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(ApiResponse.success(
                "Si el correo existe, recibirás un código de verificación", null));
    }

    /**
     * Endpoint auxiliar para el Flujo B sin correo (Flujo A).
     *
     * Permite al frontend saber si el agente con el DNI dado tiene correo registrado,
     * para mostrar el mensaje adecuado DESPUÉS de llamar a /forgot-password:
     *   - true  → "Revisa tu correo, te enviamos el código"
     *   - false → "Contacta a tu administrador para que restablezca tu contraseña"
     *
     * No revela si el DNI existe: retorna false para DNI inexistente y para agente sin correo.
     *
     * GET /api/v1/auth/forgot-password/tiene-correo?dni=12345678
     */
    @GetMapping("/forgot-password/tiene-correo")
    @Operation(summary = "Indica si el agente tiene correo registrado para recuperación autónoma")
    public ResponseEntity<ApiResponse<Boolean>> agentetieneCorreo(
            @RequestParam @NotBlank @Size(max = 20) String dni) {
        return ResponseEntity.ok(ApiResponse.success(authService.agentetieneCorreo(dni)));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.success("Contraseña actualizada exitosamente", null));
    }
}


