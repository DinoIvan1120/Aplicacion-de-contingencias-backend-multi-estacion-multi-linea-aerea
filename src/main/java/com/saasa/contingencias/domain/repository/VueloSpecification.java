package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.enumeration.ContingenciaEnum;
import com.saasa.contingencias.domain.enumeration.EstadoVueloEnum;
import com.saasa.contingencias.domain.model.Vuelo;
import org.springframework.data.jpa.domain.Specification;

/**
 * Specification dinámica para búsqueda/filtrado de vuelos.
 *
 * Estrategia idéntica a UsuarioSpecification:
 * - Una sola query SQL con WHERE dinámico (solo se añaden las cláusulas
 *   cuyos parámetros fueron proporcionados).
 * - Filtros de texto usan LIKE case-insensitive.
 * - Filtros exactos (tipoContingencia, estado) usan igualdad directa.
 * - Predicados combinados con AND.
 *
 * Ejemplo de uso:
 *   GET /vuelos/buscar?aerolinea=plus&tipoContingencia=CANCELACION&estado=ACTIVO
 *   GET /vuelos/buscar?codigoVuelo=PU3&origen=MAD
 */
public class VueloSpecification {

    private VueloSpecification() {}

    /**
     * Construye la Specification completa a partir de los filtros opcionales.
     * Los parámetros null o en blanco son ignorados.
     */
    public static Specification<Vuelo> build(
            String aerolinea,
            String codigoVuelo,
            String origen,
            String destino,
            ContingenciaEnum tipoContingencia,
            EstadoVueloEnum estado) {

        return Specification
                .where(porAerolinea(aerolinea))
                .and(porCodigoVuelo(codigoVuelo))
                .and(porOrigen(origen))
                .and(porDestino(destino))
                .and(porTipoContingencia(tipoContingencia))
                .and(porEstado(estado));
    }

    // ─── Filtro por aerolínea (LIKE parcial, case-insensitive) ────────────────
    private static Specification<Vuelo> porAerolinea(String aerolinea) {
        if (aerolinea == null || aerolinea.isBlank()) return null;
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("aerolinea")),
                        "%" + aerolinea.trim().toLowerCase() + "%");
    }

    // ─── Filtro por código de vuelo (LIKE parcial, case-insensitive) ──────────
    private static Specification<Vuelo> porCodigoVuelo(String codigoVuelo) {
        if (codigoVuelo == null || codigoVuelo.isBlank()) return null;
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("codigoVuelo")),
                        "%" + codigoVuelo.trim().toLowerCase() + "%");
    }

    // ─── Filtro por origen IATA (LIKE parcial, case-insensitive) ─────────────
    private static Specification<Vuelo> porOrigen(String origen) {
        if (origen == null || origen.isBlank()) return null;
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("origen")),
                        "%" + origen.trim().toLowerCase() + "%");
    }

    // ─── Filtro por destino IATA (LIKE parcial, case-insensitive) ────────────
    private static Specification<Vuelo> porDestino(String destino) {
        if (destino == null || destino.isBlank()) return null;
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("destino")),
                        "%" + destino.trim().toLowerCase() + "%");
    }

    // ─── Filtro exacto por tipo de contingencia (enum) ────────────────────────
    private static Specification<Vuelo> porTipoContingencia(ContingenciaEnum tipo) {
        if (tipo == null) return null;
        return (root, query, cb) ->
                cb.equal(root.get("tipoContingencia"), tipo);
    }

    // ─── Filtro exacto por estado (ACTIVO | ANULADO) ──────────────────────────
    private static Specification<Vuelo> porEstado(EstadoVueloEnum estado) {
        if (estado == null) return null;
        return (root, query, cb) ->
                cb.equal(root.get("estado"), estado);
    }
}
