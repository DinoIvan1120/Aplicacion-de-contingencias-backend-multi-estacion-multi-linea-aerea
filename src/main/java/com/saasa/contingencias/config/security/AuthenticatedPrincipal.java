package com.saasa.contingencias.config.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Principal de Spring Security enriquecido con las estaciones a las que el
 * usuario autenticado tiene acceso. Lo arma JwtFilter a partir de los
 * claims del token, para que el resto de la aplicación (p. ej. el filtro
 * centralizado por Specification) las lea directamente del
 * SecurityContext sin tener que volver a decodificar el JWT.
 */
public class AuthenticatedPrincipal extends User {

    /** Pares estación+línea aérea activos del usuario. Vacía = Administrador Global. */
    private final List<ScopeEstacionLinea> scopes;

    public AuthenticatedPrincipal(String subject, Collection<? extends GrantedAuthority> authorities,
                                  List<ScopeEstacionLinea> scopes) {
        super(subject, "", authorities);
        this.scopes = scopes == null ? Collections.emptyList() : List.copyOf(scopes);
    }

    public List<ScopeEstacionLinea> getScopes() {
        return scopes;
    }

    /** Estaciones activas del usuario (sin duplicados). Vacía = Administrador Global. */
    public List<Long> getEstacionIds() {
        return scopes.stream().map(ScopeEstacionLinea::estacionId).distinct().toList();
    }

    /** true si el usuario no tiene estaciones asignadas (ve todo, sin restricción). */
    public boolean esAdministradorGlobal() {
        return scopes.isEmpty();
    }
}