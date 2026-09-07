package com.saasa.contingencias.config.exception;

/**
 * Excepción lanzada cuando se supera el límite de intentos de login.
 *
 * Aunque actualmente el filtro responde directamente con 429 sin lanzar esta excepción,
 * se incluye en la jerarquía de AppException para:
 *   - Mantener consistencia con el modelo de excepciones del proyecto.
 *   - Permitir su uso en futuros contextos (e.g. validaciones programáticas en servicios).
 *   - Facilitar tests unitarios que verifiquen el comportamiento de rate limiting.
 */
public final class TooManyRequestsException extends AppException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
