package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Extiende JpaSpecificationExecutor para soportar búsqueda dinámica
 * multi-campo con criterios opcionales combinados mediante AND.
 * Es la estrategia más óptima: una sola query SQL generada dinámicamente,
 * sin N métodos derivados ni múltiples consultas.
 */
public interface UsuarioRepository extends JpaRepository<Usuario, Long>,
        JpaSpecificationExecutor<Usuario> {
    Optional<Usuario> findByCorreo(String correo);
    boolean existsByCorreo(String correo);
    boolean existsByCodigoEmpleado(String codigoEmpleado);

    /**
     * Búsqueda por documento (DNI) — usado para login y recuperación de
     * contraseña del rol AGENTE_SAASA.
     */
    Optional<Usuario> findByDocumento(String documento);

    /**
     * Unicidad de documento: se verifica en el service al registrar un agente
     * para evitar que dos agentes compartan el mismo DNI.
     */
    boolean existsByDocumento(String documento);

    /**
     * MEJORA 2 & 3 — Obtiene la aerolínea asociada al usuario de rol LINEA_AEREA.
     * Se asume que el codigoEmpleado contiene la referencia a su aerolínea,
     * o bien el nombre de la aerolínea se almacena en el campo 'documento'.
     * Para LINEA_AEREA el campo 'documento' contiene el nombre de su aerolínea.
     */
    @Query("SELECT u.documento FROM Usuario u WHERE u.id = :id")
    Optional<String> findDocumentoById(@Param("id") Long id);
}
