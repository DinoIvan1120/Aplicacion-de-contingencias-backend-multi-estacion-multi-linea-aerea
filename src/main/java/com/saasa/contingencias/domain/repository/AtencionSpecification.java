package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.dto.request.ReporteFilterRequest;
import com.saasa.contingencias.domain.model.Atencion;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;


/**
 * MEJORA 2 & 3 — Especificaciones JPA para filtrado dinámico de Atenciones.
 *
 * Aplica filtros según:
 *   - Campos de búsqueda (correlativo, pnr, nombre, vuelo, fechas)
 *   - Rol del usuario autenticado:
 *     · LINEA_AEREA  → solo ve atenciones de vuelos de su aerolínea
 *     · PROVEEDOR    → solo ve atenciones con sus servicios asignados
 *     · Otros roles  → ven todo (con filtros opcionales)
 */
public class AtencionSpecification {

    private AtencionSpecification() {}

    /**
     * Construye la Specification completa combinando filtros de campos y de rol.
     */
    public static Specification<Atencion> build(
            ReporteFilterRequest filtros,
            String rolUsuario,
            String aerolineaUsuario,
            Long proveedorIdUsuario) {

        return Specification
                .where(porRol(rolUsuario, aerolineaUsuario, proveedorIdUsuario))
                .and(porCorrelativo(filtros))
                .and(porPnr(filtros))
                .and(porNombrePasajero(filtros))
                .and(porVuelo(filtros))
                .and(porFechaDesde(filtros))
                .and(porFechaHasta(filtros))
                .and(porAgente(filtros))
                .and(porEstado(filtros))
                .and(ocultarAnuladosSegunRol(rolUsuario));
    }

    // ─── Filtro por rol ──────────────────────────────────────────────────────────────

    /**
     * MEJORA 2: LINEA_AEREA solo ve atenciones de su aerolínea.
     * MEJORA 3: PROVEEDOR solo ve atenciones con sus servicios.
     */
    private static Specification<Atencion> porRol(
            String rolUsuario, String aerolineaUsuario, Long proveedorIdUsuario) {

        if (rolUsuario == null) return null;

        return switch (rolUsuario) {
            case "LINEA_AEREA" -> (root, query, cb) -> {
                // JOIN a.vuelo v WHERE v.aerolinea = :aerolineaUsuario
                Join<Object, Object> vuelo = root.join("vuelo", JoinType.INNER);
                return cb.equal(vuelo.get("aerolinea"), aerolineaUsuario);
            };
            case "PROVEEDOR" -> (root, query, cb) -> {
                // JOIN ServicioAsignado sa WHERE sa.servicioProveedor.proveedor.id = :proveedorId
                query.distinct(true);
                Subquery<Long> sub = query.subquery(Long.class);
                var saRoot = sub.from(com.saasa.contingencias.domain.model.ServicioAsignado.class);
                sub.select(saRoot.get("atencion").get("id"))
                        .where(cb.equal(
                                saRoot.get("vueloRecurso").get("proveedor").get("id"),
                                proveedorIdUsuario
                        ));
                return root.get("id").in(sub);
            };
            // ADMINISTRADOR, LIDER_SAASA, AGENTE_SAASA → sin restricción de rol
            default -> null;
        };
    }

    // ─── Filtros de campos ───────────────────────────────────────────────────────────

    private static Specification<Atencion> porCorrelativo(ReporteFilterRequest f) {
        if (f == null || f.correlativo() == null || f.correlativo().isBlank()) return null;
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("numeroCorrelativo")),
                        "%" + f.correlativo().toLowerCase() + "%");
    }

    private static Specification<Atencion> porPnr(ReporteFilterRequest f) {
        if (f == null || f.pnr() == null || f.pnr().isBlank()) return null;
        return (root, query, cb) ->
                cb.like(cb.upper(root.get("pnr")),
                        "%" + f.pnr().toUpperCase() + "%");
    }

    private static Specification<Atencion> porNombrePasajero(ReporteFilterRequest f) {
        if (f == null || f.nombrePasajero() == null || f.nombrePasajero().isBlank()) return null;
        return (root, query, cb) -> {
            String patron = "%" + f.nombrePasajero().toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("nombre")), patron),
                    cb.like(cb.lower(root.get("apellido")), patron)
            );
        };
    }

    private static Specification<Atencion> porVuelo(ReporteFilterRequest f) {
        if (f == null || f.vueloId() == null) return null;
        return (root, query, cb) ->
                cb.equal(root.get("vuelo").get("id"), f.vueloId());
    }

    private static Specification<Atencion> porFechaDesde(ReporteFilterRequest f) {
        if (f == null || f.fechaDesde() == null) return null;
        return (root, query, cb) ->
                cb.greaterThanOrEqualTo(
                        root.get("createdAt").as(java.time.LocalDate.class),
                        f.fechaDesde()
                );
    }

    private static Specification<Atencion> porFechaHasta(ReporteFilterRequest f) {
        if (f == null || f.fechaHasta() == null) return null;
        return (root, query, cb) ->
                cb.lessThanOrEqualTo(
                        root.get("createdAt").as(java.time.LocalDate.class),
                        f.fechaHasta()
                );
    }

    private static Specification<Atencion> porAgente(ReporteFilterRequest f) {
        if (f == null || f.agenteId() == null) return null;
        return (root, query, cb) ->
                cb.equal(root.get("atendidoPor").get("id"), f.agenteId());
    }

    private static Specification<Atencion> porEstado(ReporteFilterRequest f) {
        if (f == null || f.estado() == null || f.estado().isBlank()) return null;
        return (root, query, cb) -> cb.equal(
                root.get("estado"),
                com.saasa.contingencias.domain.enumeration.EstadoAtencionEnum.valueOf(f.estado()));
    }

    /**
     * Los roles LINEA_AEREA y PROVEEDOR nunca deben ver registros anulados,
     * sin importar qué filtros envíen. Se aplica como AND obligatorio,
     * de modo que si alguien intenta forzar estado=ANULADO desde el cliente,
     * la combinación de filtros simplemente no devuelve resultados (fail-closed).
     */
    private static Specification<Atencion> ocultarAnuladosSegunRol(String rolUsuario) {
        if (!"LINEA_AEREA".equals(rolUsuario) && !"PROVEEDOR".equals(rolUsuario)) return null;
        return (root, query, cb) -> cb.notEqual(
                root.get("estado"),
                com.saasa.contingencias.domain.enumeration.EstadoAtencionEnum.ANULADO);
    }
}
