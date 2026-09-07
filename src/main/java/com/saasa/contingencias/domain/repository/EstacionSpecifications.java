package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.model.EstacionScopedEntity;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

/**
 * Mecanismo técnico de aislamiento: predicado centralizado
 * `estacion_id = :estacionId AND linea_aerea_id = :lineaAereaId`,
 * aplicado vía el patrón Specification ya usado en el proyecto
 * (AtencionSpecification, VueloSpecification, UsuarioSpecification) — no
 * vía `@TenantId` de Hibernate.
 *
 * A diferencia de la versión anterior (solo estación, con `IN` sobre
 * varias estaciones posibles), el contexto ya llega resuelto a un ÚNICO
 * par estación+línea aérea: lo resuelve
 * `EstacionContext#resolverContextoActivo(Long, Long)` a partir del
 * "contexto de trabajo" activo (selector de estación/línea del frontend),
 * que ahora es obligatorio tanto para Administrador Global como para
 * cualquier usuario sin línea aérea fija.
 *
 * Cubre cualquier entidad que extienda EstacionScopedEntity (Vuelo,
 * Proveedor, RegistroVueloDiario, Atencion, ServicioProveedor,
 * VueloRecurso, ServicioAsignado, EnvioPdf). Usuario queda fuera de este
 * mecanismo a propósito: su acceso se modela con `usuario_estacion`
 * (muchos-a-muchos, ahora con línea aérea opcional por fila).
 *
 * NO cubre las 6 consultas SQL nativas de AtencionRepository — esas se
 * parchean manualmente, ver el javadoc de ese repositorio.
 */
public final class EstacionSpecifications {

    private EstacionSpecifications() {}

    /**
     * @param contexto par estación+línea aérea activo, ya resuelto por
     *                 EstacionContext.resolverContextoActivo(...).
     *                 null = sin contexto de trabajo activo (no debería
     *                 ocurrir en un endpoint de lectura ya protegido, pero
     *                 se trata como "sin restricción" para no romper
     *                 llamadas internas fuera de un request HTTP).
     */
    public static <T extends EstacionScopedEntity> Specification<T> porContextoDelUsuario(ScopeEstacionLinea contexto) {
        if (contexto == null) {
            return null;
        }
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("estacionId"), contexto.estacionId()),
                cb.equal(root.get("lineaAereaId"), contexto.lineaAereaId()));
    }

    /**
     * @deprecated usar porContextoDelUsuario(ScopeEstacionLinea): el
     * filtro ahora también acota por línea aérea, no solo por estación.
     */
    @Deprecated
    public static <T extends EstacionScopedEntity> Specification<T> porEstacionesDelUsuario(List<Long> estacionIds) {
        if (estacionIds == null || estacionIds.isEmpty()) {
            return null; // Administrador Global: sin restricción.
        }
        return (root, query, cb) -> root.get("estacionId").in(estacionIds);
    }
}
