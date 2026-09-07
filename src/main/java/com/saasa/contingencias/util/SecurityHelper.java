package com.saasa.contingencias.util;

import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.repository.UsuarioRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Componente de utilidad de seguridad reutilizable por todos los controllers.
 *
 * Centraliza la resolución del usuario autenticado (correo → ID, rol)
 * a partir del contexto de Spring Security, evitando que cada controller
 * inyecte UsuarioRepository y repita la misma lógica.
 *
 * Ubicado en 'util' porque es un helper transversal sin lógica de negocio.
 */
@Component
public class SecurityHelper {

    private final UsuarioRepository usuarioRepository;

    public SecurityHelper(UsuarioRepository usuarioRepository) {
        this.usuarioRepository = usuarioRepository;
    }

    /**
     * Resuelve el ID del usuario autenticado a partir de su correo (username en JWT).
     *
     * @param user UserDetails inyectado por @AuthenticationPrincipal
     * @return ID del usuario en base de datos
     * @throws RecursoNoEncontradoException si el correo no existe en BD
     */
    public Long getUsuarioId(UserDetails user) {
        return usuarioRepository.findByCorreo(user.getUsername())
                .or(() -> usuarioRepository.findByDocumento(user.getUsername()))
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado"))
                .getId();
    }

    /**
     * Extrae el rol del usuario autenticado desde el contexto de Spring Security.
     * Elimina el prefijo "ROLE_" que Spring añade internamente.
     *
     * @param user UserDetails inyectado por @AuthenticationPrincipal
     * @return nombre del rol sin prefijo (ej: "ADMINISTRADOR", "AGENTE_SAASA")
     */
    public String getRol(UserDetails user) {
        return user.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .orElse(null);
    }
}
