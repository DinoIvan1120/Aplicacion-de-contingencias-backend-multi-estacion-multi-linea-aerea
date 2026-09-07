package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.AsignarEstacionRequest;
import com.saasa.contingencias.domain.dto.request.UsuarioRequest;
import com.saasa.contingencias.domain.dto.response.UsuarioEstacionResponse;
import com.saasa.contingencias.domain.dto.response.UsuarioResponse;
import com.saasa.contingencias.domain.enumeration.RolEnum;
import org.springframework.data.domain.*;
import java.util.List;

public interface IUsuarioService {
    Page<UsuarioResponse> findAll(Pageable pageable);

    /**
     * Búsqueda dinámica multi-campo.
     * Todos los parámetros son opcionales; los null/blank se ignoran.
     *
     * @param nombre         busca en nombre O apellido (LIKE parcial)
     * @param correo         busca en correo (LIKE parcial)
     * @param documento      busca en documento (LIKE parcial)
     * @param codigoEmpleado busca en código de empleado (LIKE parcial)
     * @param rol            filtro exacto por rol (ADMINISTRADOR, LIDER_SAASA, etc.)
     * @param estado         filtro exacto: 1=activo, 0=inactivo
     * @param pageable       paginación y ordenamiento
     */
    Page<UsuarioResponse> buscar(String nombre, String correo, String documento,
                                 String codigoEmpleado, RolEnum rol, Integer estado,
                                 Pageable pageable);

    UsuarioResponse create(UsuarioRequest request);
    UsuarioResponse update(Long id, UsuarioRequest request);
    void changeEstado(Long id, Integer estado);

    // ─── Estaciones asignadas al usuario (Fase 2 — multi-estación) ────────────
    // Documento Funcional Multi-Estación v1.1, secciones 7.3b y 9.3:
    // un usuario sin estaciones asignadas es Administrador Global.

    /** Estaciones activas asignadas al usuario. Lista vacía = Administrador Global. */
    List<UsuarioEstacionResponse> findEstaciones(Long usuarioId);

    /** Asigna (o reactiva) una estación para el usuario. */
    UsuarioEstacionResponse asignarEstacion(Long usuarioId, AsignarEstacionRequest request);

    /**
     * Desactiva el acceso del usuario a una estación+línea específica
     * (no borra el vínculo histórico). Se identifica por el id de la
     * relación (UsuarioEstacion.id, ya incluido en UsuarioEstacionResponse),
     * no por estacionId: un usuario puede tener varias filas para la MISMA
     * estación con líneas aéreas distintas, así que estacionId solo ya no
     * identifica una fila única.
     */
    void quitarEstacion(Long usuarioId, Long relacionId);
}
