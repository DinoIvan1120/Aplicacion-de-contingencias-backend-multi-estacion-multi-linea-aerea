package com.saasa.contingencias.config.security;

import com.saasa.contingencias.config.exception.AccesoDenegadoException;
import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.domain.model.EstacionScopedEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Mecanismo central de resolución del contexto estación+línea aérea del
 * usuario autenticado: "cada usuario solo puede ver/crear información de
 * su(s) estación(es) y, dentro de ellas, de su(s) línea(s) aérea(s)
 * asignada(s); el Administrador Global y el Administrador de Estación sin
 * línea fija deben elegir explícitamente una estación+línea activa (el
 * 'contexto de trabajo') para operar".
 *
 * La línea aérea pasó de ser un dato de negocio dentro de la estación a
 * ser, junto con la estación, el eje de aislamiento real: ver el
 * Documento Funcional Multi-Estación, sección 3 (revisión posterior a la
 * v1.1) — "estación + línea aérea como par de aislamiento".
 */
@Component
public class EstacionContext {

    /**
     * Pares estación+línea aérea del usuario autenticado. Lista vacía =
     * Administrador Global (sin restricción). También vacía si no hay
     * usuario autenticado en el contexto (p. ej. llamadas internas fuera
     * de un request HTTP).
     */
    public List<ScopeEstacionLinea> scopesActuales() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AuthenticatedPrincipal p)) {
            return Collections.emptyList();
        }
        return p.getScopes();
    }

    /** Estaciones activas del usuario (sin duplicados). Vacía = Administrador Global. */
    public List<Long> estacionesActuales() {
        return scopesActuales().stream().map(ScopeEstacionLinea::estacionId).distinct().toList();
    }

    /** true si el usuario autenticado es Administrador Global (sin restricción de estación ni línea). */
    public boolean esAdministradorGlobal() {
        return scopesActuales().isEmpty();
    }

    /**
     * true si, dentro de la(s) estación(es) del usuario, tiene una línea
     * aérea fija asignada en todas ellas (no necesita elegir línea porque
     * ya viene resuelta, p. ej. un Líder o Agente SAASA de una sola
     * aerolínea).
     */
    public boolean tieneLineaAereaFija() {
        List<ScopeEstacionLinea> scopes = scopesActuales();
        return !scopes.isEmpty() && scopes.stream().noneMatch(s -> s.lineaAereaId() == null);
    }

    /**
     * Protege el acceso directo por ID (p. ej. GET /vuelos/{id}): lanza
     * AccesoDenegadoException si el registro pertenece a una estación o
     * línea aérea fuera del alcance del usuario. Administrador Global
     * siempre pasa. Un registro sin estacionId/lineaAereaId (ventana de
     * migración) también se deja pasar para no romper datos aún no
     * backfilleados.
     */
    public void validarAccesoLectura(EstacionScopedEntity entidad) {
        List<ScopeEstacionLinea> propios = scopesActuales();
        if (propios.isEmpty()) return; // Administrador Global
        Long estacionDelRegistro = entidad.getEstacionId();
        Long lineaDelRegistro = entidad.getLineaAereaId();
        if (estacionDelRegistro == null) return; // aún sin backfillear
        boolean tieneAcceso = propios.stream().anyMatch(s ->
                s.estacionId().equals(estacionDelRegistro)
                        && (s.lineaAereaId() == null // el usuario ve todas las líneas de esa estación
                        || lineaDelRegistro == null // registro aún sin backfillear a nivel de línea
                        || s.lineaAereaId().equals(lineaDelRegistro)));
        if (!tieneAcceso) {
            throw new AccesoDenegadoException("No tiene acceso a este recurso: pertenece a otra estación o línea aérea");
        }
    }

    /**
     * Resuelve con qué estación+línea aérea se debe grabar un registro
     * nuevo, o con qué par se debe filtrar una lectura.
     * - Usuario con exactamente un par fijo (estación+línea): se usa ese,
     *   rechazando si el cliente pidió explícitamente uno distinto.
     * - Administrador Global, o usuario sin línea fija (Administrador de
     *   Estación, o con varias estaciones/líneas): el "contexto de
     *   trabajo" (estacionId + lineaAereaId) es obligatorio y debe venir
     *   del selector de estación/línea del frontend en cada request.
     */
    public ScopeEstacionLinea resolverContextoActivo(Long estacionIdSolicitado, Long lineaAereaIdSolicitado) {
        // Si el controller no recibió estacionId/lineaAereaId explícitos (caso
        // típico de las lecturas: GET /vuelos/itinerario-hoy, GET /registros-diarios/hoy,
        // reportes, auditoría), se usa el contexto que el frontend manda en
        // los headers X-Estacion-Id / X-Linea-Aerea-Id en TODA petición.
        Long estacionEfectivo = estacionIdSolicitado != null ? estacionIdSolicitado : ContextoActivoHolder.getEstacionId();
        Long lineaAereaEfectiva = lineaAereaIdSolicitado != null ? lineaAereaIdSolicitado : ContextoActivoHolder.getLineaAereaId();
        return resolverContextoActivoInterno(estacionEfectivo, lineaAereaEfectiva);
    }

    /** Atajo para lecturas que no reciben parámetros propios: resuelve solo desde los headers del contexto activo. */
    public ScopeEstacionLinea resolverContextoActivo() {
        return resolverContextoActivo(null, null);
    }

    private ScopeEstacionLinea resolverContextoActivoInterno(Long estacionIdSolicitado, Long lineaAereaIdSolicitado) {
        List<ScopeEstacionLinea> propios = scopesActuales();

        if (propios.size() == 1 && propios.get(0).lineaAereaId() != null) {
            ScopeEstacionLinea unico = propios.get(0);
            if (estacionIdSolicitado != null && !estacionIdSolicitado.equals(unico.estacionId())) {
                throw new AccesoDenegadoException("No tiene acceso a la estación " + estacionIdSolicitado);
            }
            if (lineaAereaIdSolicitado != null && !lineaAereaIdSolicitado.equals(unico.lineaAereaId())) {
                throw new AccesoDenegadoException("No tiene acceso a la línea aérea " + lineaAereaIdSolicitado);
            }
            return unico;
        }

        if (estacionIdSolicitado == null || lineaAereaIdSolicitado == null) {
            throw new BadRequestException(
                    "Debe especificar estacionId y lineaAereaId (contexto de trabajo activo): el usuario "
                            + "tiene acceso a más de una estación/línea, o es Administrador Global");
        }
        if (!propios.isEmpty()) {
            boolean permitido = propios.stream().anyMatch(s ->
                    s.estacionId().equals(estacionIdSolicitado)
                            && (s.lineaAereaId() == null || s.lineaAereaId().equals(lineaAereaIdSolicitado)));
            if (!permitido) {
                throw new AccesoDenegadoException(
                        "No tiene acceso a la estación " + estacionIdSolicitado + " / línea " + lineaAereaIdSolicitado);
            }
        }
        return new ScopeEstacionLinea(estacionIdSolicitado, lineaAereaIdSolicitado);
    }

    //Cambio

    /**
     * Variante de {@link #resolverContextoActivo(Long, Long)} para LECTURAS
     * (listados, historial, reportes, auditoría): a diferencia de la
     * escritura —crear/editar un registro necesita saber a qué línea aérea
     * concreta pertenece—, leer admite perfectamente
     * {@code lineaAereaId == null} como "todas las líneas de esa estación".
     * Es el mismo significado que ya documenta {@link ScopeEstacionLinea} y
     * que {@link #validarAccesoLectura} ya toleraba; este método aplica esa
     * misma tolerancia al resolver el contexto, no solo al validar acceso
     * directo por ID.
     *
     * Sigue exigiendo la estación (o heredándola del único par fijo del
     * usuario): sin estación no hay qué listar. Cuando el frontend manda
     * "Continuar sin filtrar por línea" (X-Estacion-Id sin X-Linea-Aerea-Id),
     * este método ya no lanza 400 — antes de este fix, resolverContextoActivo()
     * exigía ambos también para lecturas y ese caso quedaba sin soportar.
     */
    public ScopeEstacionLinea resolverContextoActivoLectura(Long estacionIdSolicitado, Long lineaAereaIdSolicitado) {
        Long estacionEfectiva = estacionIdSolicitado != null ? estacionIdSolicitado : ContextoActivoHolder.getEstacionId();
        Long lineaAereaEfectiva = lineaAereaIdSolicitado != null ? lineaAereaIdSolicitado : ContextoActivoHolder.getLineaAereaId();

        List<ScopeEstacionLinea> propios = scopesActuales();

        if (propios.size() == 1 && propios.get(0).lineaAereaId() != null) {
            ScopeEstacionLinea unico = propios.get(0);
            if (estacionEfectiva != null && !estacionEfectiva.equals(unico.estacionId())) {
                throw new AccesoDenegadoException("No tiene acceso a la estación " + estacionEfectiva);
            }
            if (lineaAereaEfectiva != null && !lineaAereaEfectiva.equals(unico.lineaAereaId())) {
                throw new AccesoDenegadoException("No tiene acceso a la línea aérea " + lineaAereaEfectiva);
            }
            return unico;
        }

        if (estacionEfectiva == null) {
            throw new BadRequestException(
                    "Debe especificar estacionId (contexto de trabajo activo): el usuario "
                            + "tiene acceso a más de una estación/línea, o es Administrador Global");
        }
        if (!propios.isEmpty()) {
            boolean permitido = propios.stream().anyMatch(s ->
                    s.estacionId().equals(estacionEfectiva)
                            && (s.lineaAereaId() == null
                            || lineaAereaEfectiva == null
                            || s.lineaAereaId().equals(lineaAereaEfectiva)));
            if (!permitido) {
                throw new AccesoDenegadoException(
                        "No tiene acceso a la estación " + estacionEfectiva
                                + (lineaAereaEfectiva != null ? " / línea " + lineaAereaEfectiva : ""));
            }
        }
        return new ScopeEstacionLinea(estacionEfectiva, lineaAereaEfectiva);
    }

    /** Atajo para lecturas que no reciben parámetros propios (el caso más común: listados, /hoy, /mis-registros). */
    public ScopeEstacionLinea resolverContextoActivoLectura() {
        return resolverContextoActivoLectura(null, null);
    }

    //Cambio

    /** @deprecated usar {@link #resolverContextoActivo(Long, Long)}: la línea aérea ahora también es obligatoria. */
    @Deprecated
    public Long resolverEstacionParaEscritura(Long estacionIdSolicitada) {
        List<Long> propias = estacionesActuales();
        if (propias.size() == 1) {
            Long unica = propias.get(0);
            if (estacionIdSolicitada != null && !estacionIdSolicitada.equals(unica)) {
                throw new AccesoDenegadoException(
                        "No tiene acceso a la estación " + estacionIdSolicitada + " para crear este registro");
            }
            return unica;
        }
        if (estacionIdSolicitada == null) {
            throw new BadRequestException(
                    "Debe especificar estacionId: el usuario tiene acceso a más de una estación (o es Administrador Global)");
        }
        if (!propias.isEmpty() && !propias.contains(estacionIdSolicitada)) {
            throw new AccesoDenegadoException("No tiene acceso a la estación " + estacionIdSolicitada);
        }
        return estacionIdSolicitada;
    }
}