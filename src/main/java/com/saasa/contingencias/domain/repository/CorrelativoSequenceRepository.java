package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.CorrelativoSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio para la tabla de secuencia de correlativos.
 *
 * Solo necesita JpaRepository: el único caso de uso es
 * save(new CorrelativoSequence()) para obtener un id atómico.
 * No se necesitan queries adicionales.
 */
public interface CorrelativoSequenceRepository
        extends JpaRepository<CorrelativoSequence, Long> {

    @Modifying
    @Query(value = """
        INSERT INTO correlativo_sequence (estacion_id, linea_aerea_id, ultimo_numero)
        VALUES (:estacionId, :lineaAereaId, 1)
        ON DUPLICATE KEY UPDATE ultimo_numero = ultimo_numero + 1
        """, nativeQuery = true)
    void incrementarYObtener(@Param("estacionId") Long estacionId, @Param("lineaAereaId") Long lineaAereaId);

    // Se lee directo de la fila, filtrando por la combinación, en vez de
    // depender de LAST_INSERT_ID() (variable de sesión de MySQL que solo
    // se actualiza en la rama UPDATE de un INSERT ... ON DUPLICATE KEY —
    // en un INSERT nuevo devuelve el id autogenerado de la tabla, no
    // ultimo_numero, y por eso el primer voucher de cada combinación
    // estación+aerolínea salía con un número que no era 1).
    @Query(value = """
        SELECT ultimo_numero FROM correlativo_sequence
        WHERE estacion_id = :estacionId AND linea_aerea_id = :lineaAereaId
        """, nativeQuery = true)
    Long obtenerUltimoNumeroGenerado(@Param("estacionId") Long estacionId, @Param("lineaAereaId") Long lineaAereaId);
}
