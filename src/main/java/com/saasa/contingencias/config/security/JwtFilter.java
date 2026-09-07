package com.saasa.contingencias.config.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = req.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                if (jwtUtil.isTokenValid(token)) {
                    // subject es correo para todos los roles, o documento para AGENTE_SAASA sin correo
                    String subject = jwtUtil.extractCorreo(token);
                    String rol     = jwtUtil.extractRol(token);
                    // Pares estación+línea aérea del claim del JWT. Vacía = Administrador Global.
                    List<ScopeEstacionLinea> scopes = jwtUtil.extractScopes(token);
                    var principal = new AuthenticatedPrincipal(
                            subject, List.of(new SimpleGrantedAuthority("ROLE_" + rol)), scopes);
                    var auth = new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + rol))
                    );
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
            // Contexto de trabajo activo (estación+línea aérea) que el selector
            // del topbar manda en CADA request, lectura o escritura — corrige
            // el bug histórico donde el switcher solo viajaba al crear registros.
            ContextoActivoHolder.set(
                    parseHeaderLong(req.getHeader(ContextoActivoHolder.HEADER_ESTACION)),
                    parseHeaderLong(req.getHeader(ContextoActivoHolder.HEADER_LINEA_AEREA)));
            chain.doFilter(req, res);
        } finally {
            ContextoActivoHolder.clear();
        }
    }

    private static Long parseHeaderLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}