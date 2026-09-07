package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.enumeration.RolEnum;
import com.saasa.contingencias.domain.model.Usuario;
import com.saasa.contingencias.domain.model.UsuarioEstacion;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Specification dinámica para búsqueda de usuarios.
 *
 * Estrategia óptima:
 * - Una sola query SQL con WHERE dinámico (solo se añaden las cláusulas
 *   cuyos parámetros fueron proporcionados → no genera predicados vacíos).
 * - Todos los filtros de texto usan LIKE case-insensitive con trim().
 * - Filtros exactos (rol, estado) usan igualdad directa.
 * - Los predicados se combinan con AND (Specification.where().and()).
 *
 * SQL resultante (ejemplo con nombre + rol):
 *   SELECT * FROM usuarios
 *   WHERE LOWER(nombre) LIKE '%roberto%'
 *      OR LOWER(apellido) LIKE '%roberto%'
 *   AND rol = 'LIDER_SAASA'
 *
 * Indexación recomendada para mejor rendimiento en producción:
 *   CREATE INDEX idx_usuarios_nombre    ON usuarios(nombre);
 *   CREATE INDEX idx_usuarios_correo    ON usuarios(correo);
 *   CREATE INDEX idx_usuarios_documento ON usuarios(documento);
 *   CREATE INDEX idx_usuarios_rol       ON usuarios(rol);
 *   CREATE INDEX idx_usuarios_estado    ON usuarios(estado);
 */
public class UsuarioSpecification {

    private UsuarioSpecification() {}

    /**
     * Construye la Specification completa a partir de los filtros opcionales.
     * Los parámetros null o en blanco son ignorados — no generan predicado.
     *
     * @param nombre        busca en nombre Y apellido (LIKE, case-insensitive)
     * @param correo        busca en correo (LIKE, case-insensitive)
     * @param documento     busca en documento (LIKE, case-insensitive)
     * @param codigoEmpleado busca exacto o parcial (LIKE, case-insensitive)
     * @param rol           filtro exacto por enum RolEnum
     * @param estado        filtro exacto: 1=activo, 0=inactivo
     */
    public static Specification<Usuario> build(
            String nombre,
            String correo,
            String documento,
            String codigoEmpleado,
            RolEnum rol,
            Integer estado) {

        return Specification
                .where(porNombre(nombre))
                .and(porCorreo(correo))
                .and(porDocumento(documento))
                .and(porCodigoEmpleado(codigoEmpleado))
                .and(porRol(rol))
                .and(porEstado(estado));
    }

    // ─── Filtro por nombre O apellido (busca en ambos campos) ─────────────────
    /**
     * Busca el texto en nombre Y en apellido con OR.
     * Ej: buscar "roberto" encuentra "Roberto Vargas" o "Ana Roberto".
     */
    private static Specification<Usuario> porNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) return null;
        return (root, query, cb) -> {
            String patron = "%" + nombre.trim().toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("nombre")),   patron),
                    cb.like(cb.lower(root.get("apellido")), patron)
            );
        };
    }

    // ─── Filtro por correo (LIKE parcial) ─────────────────────────────────────
    private static Specification<Usuario> porCorreo(String correo) {
        if (correo == null || correo.isBlank()) return null;
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("correo")),
                        "%" + correo.trim().toLowerCase() + "%");
    }

    // ─── Filtro por documento (LIKE parcial) ──────────────────────────────────
    private static Specification<Usuario> porDocumento(String documento) {
        if (documento == null || documento.isBlank()) return null;
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("documento")),
                        "%" + documento.trim().toLowerCase() + "%");
    }

    // ─── Filtro por código de empleado (LIKE parcial) ─────────────────────────
    private static Specification<Usuario> porCodigoEmpleado(String codigo) {
        if (codigo == null || codigo.isBlank()) return null;
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("codigoEmpleado")),
                        "%" + codigo.trim().toLowerCase() + "%");
    }

    // ─── Filtro exacto por rol (enum) ─────────────────────────────────────────
    /**
     * Filtro exacto: ADMINISTRADOR | LIDER_SAASA | AGENTE_SAASA |
     *                LINEA_AEREA | PROVEEDOR
     */
    private static Specification<Usuario> porRol(RolEnum rol) {
        if (rol == null) return null;
        return (root, query, cb) ->
                cb.equal(root.get("rol"), rol);
    }

    // ─── Filtro exacto por estado (0|1) ──────────────────────────────────────
    private static Specification<Usuario> porEstado(Integer estado) {
        if (estado == null) return null;
        return (root, query, cb) ->
                cb.equal(root.get("estado"), estado);
    }

    // ─── Filtro por contexto del Administrador que consulta (estación+línea) ──
    /**
     * Acota el listado a los usuarios que comparten el contexto de trabajo
     * activo del Administrador de estación que hace la consulta (misma
     * estación y, dentro de ella, misma línea aérea — o "todas las
     * líneas" en cualquiera de los dos lados).
     *
     * A diferencia de {@link EstacionSpecifications}, Usuario no tiene
     * columnas estacionId/lineaAereaId propias: su acceso se modela con la
     * tabla puente `usuario_estacion` (muchos-a-muchos). Por eso el filtro
     * se arma como un EXISTS contra esa tabla en vez del simple `equal`
     * que usan las demás entidades.
     *
     * Un usuario objetivo es visible cuando tiene una fila activa en
     * usuario_estacion para la estación del contexto y, si el contexto
     * trae una línea aérea seleccionada, esa fila coincide con esa línea
     * O no tiene restricción de línea (ve todas las de la estación) —
     * mismo criterio de compatibilidad que ya usa
     * EstacionContext#validarAccesoLectura para otras entidades.
     *
     * @param contexto par estación+línea activo del Administrador que
     *                 consulta, ya resuelto por
     *                 EstacionContext#resolverContextoActivoLectura().
     *                 null = Administrador Global, sin restricción
     *                 (spec nula, no se agrega ningún predicado).
     */
    public static Specification<Usuario> porContextoAdmin(ScopeEstacionLinea contexto) {
        if (contexto == null) return null; // Administrador Global
        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            Root<UsuarioEstacion> ue = sub.from(UsuarioEstacion.class);

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(ue.get("usuario"), root));
            predicates.add(cb.equal(ue.get("estado"), 1));
            predicates.add(cb.equal(ue.get("estacion").get("id"), contexto.estacionId()));
            if (contexto.lineaAereaId() != null) {
                predicates.add(cb.or(
                        cb.isNull(ue.get("lineaAerea")),
                        cb.equal(ue.get("lineaAerea").get("id"), contexto.lineaAereaId())
                ));
            }

            sub.select(ue.get("id")).where(predicates.toArray(new Predicate[0]));
            return cb.exists(sub);
        };
    }
}
