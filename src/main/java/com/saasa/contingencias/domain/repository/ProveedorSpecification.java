package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import com.saasa.contingencias.domain.model.Proveedor;
import org.springframework.data.jpa.domain.Specification;

/**
 * Specification dinámica para Proveedor (mismo criterio que VueloSpecification
 * / UsuarioSpecification).
 *
 * Incluye el filtro por línea aérea (RN-802, Fase 3): "un mismo proveedor
 * físico se registra como un Proveedor independiente por cada línea aérea"
 * — así que, a diferencia del filtro por estación (que es de sesión, ver
 * EstacionSpecifications), el filtro por línea aérea es una regla de
 * negocio normal que se aplica según la línea aérea del vuelo/atención en
 * cuestión, no del usuario autenticado.
 */
public final class ProveedorSpecification {

    private ProveedorSpecification() {}

    public static Specification<Proveedor> build(TipoProveedorEnum tipo, Integer estado, Long lineaAereaId) {
        Specification<Proveedor> spec = Specification.where(porTipo(tipo)).and(porEstado(estado));
        Specification<Proveedor> porLinea = porLineaAerea(lineaAereaId);
        return porLinea == null ? spec : spec.and(porLinea);
    }

    private static Specification<Proveedor> porTipo(TipoProveedorEnum tipo) {
        return tipo == null ? null : (root, query, cb) -> cb.equal(root.get("tipo"), tipo);
    }

    private static Specification<Proveedor> porEstado(Integer estado) {
        return estado == null ? null : (root, query, cb) -> cb.equal(root.get("estado"), estado);
    }

    private static Specification<Proveedor> porLineaAerea(Long lineaAereaId) {
        return lineaAereaId == null ? null : (root, query, cb) -> cb.equal(root.get("lineaAereaId"), lineaAereaId);
    }
}
