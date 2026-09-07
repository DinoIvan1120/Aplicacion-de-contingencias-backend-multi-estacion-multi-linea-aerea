package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.UsuarioEstacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UsuarioEstacionRepository extends JpaRepository<UsuarioEstacion, Long> {

    List<UsuarioEstacion> findByUsuarioId(Long usuarioId);

    List<UsuarioEstacion> findByUsuarioIdAndEstado(Long usuarioId, Integer estado);

    Optional<UsuarioEstacion> findByUsuarioIdAndEstacionId(Long usuarioId, Long estacionId);

    /**
     * Busca la fila exacta (estación + línea, esta última puede ser null)
     * para poder reactivar en vez de duplicar al reasignar. Ya no alcanza
     * con `findByUsuarioIdAndEstacionId` porque un usuario puede tener
     * varias filas para la MISMA estación con líneas distintas.
     * Se escribe a mano (no como derived query) porque `=` no matchea NULL
     * en SQL — se necesita `IS NULL` explícito para el caso "todas las
     * líneas de esa estación".
     */
    @Query("""
        SELECT ue FROM UsuarioEstacion ue
        WHERE ue.usuario.id = :usuarioId AND ue.estacion.id = :estacionId
          AND (:lineaAereaId IS NULL AND ue.lineaAerea IS NULL
               OR ue.lineaAerea.id = :lineaAereaId)
        """)
    Optional<UsuarioEstacion> findByUsuarioIdAndEstacionIdAndLineaAereaId(
            @Param("usuarioId") Long usuarioId,
            @Param("estacionId") Long estacionId,
            @Param("lineaAereaId") Long lineaAereaId);

    boolean existsByUsuarioIdAndEstado(Long usuarioId, Integer estado);

    List<UsuarioEstacion> findByEstacionId(Long estacionId);

    /**
     * @deprecated usar {@link #findActivasByUsuarioId(Long)}: el login ahora
     * necesita también la línea aérea de cada fila para construir los
     * `scopes` del JWT, no solo la estación.
     */
    @Deprecated
    @Query("SELECT ue.estacion.id FROM UsuarioEstacion ue WHERE ue.usuario.id = :usuarioId AND ue.estado = 1")
    List<Long> findEstacionIdsActivasByUsuarioId(@Param("usuarioId") Long usuarioId);

    /**
     * Filas activas (estación + línea aérea, esta última puede ser null =
     * "todas las líneas de esa estación") asignadas a un usuario — usada
     * para construir el claim `scopes` del JWT al iniciar sesión. Lista
     * vacía => Administrador Global. Reutiliza el finder
     * `findByUsuarioIdAndEstado` que ya existía en este repositorio.
     */
    default List<UsuarioEstacion> findActivasByUsuarioId(Long usuarioId) {
        return findByUsuarioIdAndEstado(usuarioId, 1);
    }
}
