package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.CodigoVerificacion;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface CodigoVerificacionRepository extends JpaRepository<CodigoVerificacion, Long> {
    Optional<CodigoVerificacion> findTopByUsuarioCorreoAndUsadoFalseOrderByCreadoEnDesc(String correo);

    /**
     * Búsqueda por usuarioId — flujo AGENTE_SAASA (login por DNI).
     * Se usa cuando el agente no tiene correo o se identificó por documento.
     */
    Optional<CodigoVerificacion> findTopByUsuarioIdAndUsadoFalseOrderByCreadoEnDesc(Long usuarioId);

    @Modifying
    @Query("UPDATE CodigoVerificacion c SET c.usado = true WHERE c.usuario.id = :usuarioId AND c.usado = false")
    void invalidarCodigosPrevios(@Param("usuarioId") Long usuarioId);
}
