package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.*;
import com.saasa.contingencias.domain.dto.response.AuthResponse;
import com.saasa.contingencias.domain.dto.response.UsuarioResponse;

public interface IAuthService {
    AuthResponse login(AuthRequest request);
    /**
     * Login unificado: correo + password (todos los roles)
     *                  o dni + password  (solo AGENTE_SAASA).
     */
    AuthResponse login(LoginRequest request);
    AuthResponse refresh(String token);
    void forgotPassword(ForgotPasswordRequest request);

    /**
     * Indica si el agente con el DNI dado tiene correo registrado.
     * Permite al frontend decidir entre flujo B (autónomo) o flujo A (via admin).
     */
    boolean agentetieneCorreo(String dni);

    void resetPassword(ResetPasswordRequest request);

    UsuarioResponse register(UsuarioRequest request);

    /**
     * Verifica que el contexto de seguridad actual corresponda a un ADMINISTRADOR.
     * Lanza AccesoDenegadoException si no lo es.
     */
    void verificarRolAdministrador();
}
