package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.RegistroVueloDiarioRequest;
import com.saasa.contingencias.domain.dto.response.CapacidadComprometidaResponse;
import com.saasa.contingencias.domain.dto.response.RegistroVueloDiarioResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Servicio para gestión de registros diarios de vuelos.
 *
 * Permite que los líderes seleccionen vuelos del itinerario y los registren
 * para el día con sus recursos asociados.
 */
public interface IRegistroVueloDiarioService {

    /**
     * LÍDER: Registra un vuelo del itinerario para el día con sus recursos.
     *
     * Flujo:
     *   1. Verifica que el vuelo del itinerario exista
     *   2. Verifica que no esté duplicado en la misma fecha
     *   3. Crea el registro con fecha y líder
     *   4. Habilita los recursos especificados
     *   5. Retorna el registro completo con contadores
     *
     * @param request Datos del registro (vueloItinerarioId, fechaRegistro, recursos)
     * @param liderazId ID del líder que registra
     * @return Registro creado con todos los recursos habilitados
     * @RecursoNoEncontradoException si el vuelo del itinerario no existe
     * @throws IllegalStateException si el vuelo ya está registrado en esa fecha
     */
    RegistroVueloDiarioResponse registrarVuelo(RegistroVueloDiarioRequest request, Long liderazId);

    /**
     * LÍDER/ADMIN: Obtiene un registro específico por ID.
     *
     * Incluye todos los recursos activos asociados.
     *
     * @param id ID del registro
     * @return Registro completo
     * @RecursoNoEncontradoException si no existe
     */
    RegistroVueloDiarioResponse obtenerPorId(Long id);

    /**
     * AGENTE: Lista SOLO los registros del día actual.
     *
     * Los agentes solo ven vuelos registrados para hoy.
     * Sin paginación porque típicamente son pocos registros por día.
     *
     * @return Lista de registros activos del día
     */
    List<RegistroVueloDiarioResponse> obtenerRegistrosDelDia();

    /**
     * LÍDER: Lista registros dentro de un rango de fechas.
     *
     * Permite al líder ver su historial agrupado por fecha.
     *
     * @param fechaInicio Fecha inicial (inclusive)
     * @param fechaFin Fecha final (inclusive)
     * @param pageable Paginación y ordenamiento
     * @return Página de registros en el rango
     */
    Page<RegistroVueloDiarioResponse> obtenerPorRangoFechas(
            LocalDate fechaInicio,
            LocalDate fechaFin,
            Pageable pageable
    );

    /**
     * LÍDER: Lista registros propios del líder autenticado.
     *
     * Opcionalmente filtra por rango de fechas.
     *
     * @param liderId ID del líder
     * @param fechaInicio Fecha inicial opcional
     * @param fechaFin Fecha final opcional
     * @param pageable Paginación y ordenamiento
     * @return Página de registros del líder
     */
    Page<RegistroVueloDiarioResponse> obtenerMisRegistros(
            Long liderId,
            LocalDate fechaInicio,
            LocalDate fechaFin,
            Pageable pageable
    );

    /**
     * ADMIN: Lista todos los registros activos.
     *
     * Para vista de administración general.
     *
     * @param pageable Paginación y ordenamiento
     * @return Página de todos los registros
     */
    Page<RegistroVueloDiarioResponse> obtenerTodos(Pageable pageable);

    /**
     * LÍDER: Actualiza recursos de un registro existente.
     *
     * Permite modificar los recursos habilitados (agregar/quitar hoteles, transportes, etc).
     * NO modifica el vuelo ni la fecha.
     *
     * @param id ID del registro
     * @param request Nuevos datos (solo recursos y observaciones se actualizan)
     * @param liderId ID del líder que actualiza
     * @return Registro actualizado
     * Recurso no encontrado si no existe
     */
    RegistroVueloDiarioResponse actualizarRecursos(
            Long id,
            RegistroVueloDiarioRequest request,
            Long liderId
    );

    /**
     * LÍDER: Elimina (soft-delete) un registro.
     *
     * Marca el registro como inactivo.
     * Los recursos asociados también se desactivan.
     *
     * @param id ID del registro
     * @param liderId ID del líder que elimina (validación de permisos)
     * @Recurso no encontrado si no existe
     * @ IllegalStateException si hay atenciones activas asociadas
     */
    void eliminarRegistro(Long id, Long liderId);

    /**
     * Verifica si un vuelo ya está registrado en una fecha específica.
     *
     * Útil para validaciones en el frontend antes de enviar el request.
     *
     * @param vueloItinerarioId ID del vuelo del itinerario
     * @param fecha Fecha a verificar
     * @return true si ya existe un registro activo
     */
    boolean existeRegistro(Long vueloItinerarioId, LocalDate fecha);

    /**
     * Devuelve la capacidad ya comprometida HOY por cada proveedor,
     * agrupada por proveedorId.
     *
     * Propósito: el frontend lo llama UNA vez al abrir la vista del líder
     * y usa el mapa para mostrar junto a cada proveedor en el combo cuánto
     * ya está comprometido en otros vuelos del día.
     *
     * Si excludeRegistroId != null (modo edición), se ignoran los recursos
     * del propio registro que se está editando para no contabilizarlos doble.
     *
     * Respuesta: Map<Long, CapacidadComprometidaResponse>
     *   clave   → proveedorId
     *   valor   → resumen de lo comprometido hoy por ese proveedor
     *
     * Un proveedor sin nada asignado hoy simplemente no aparece en el mapa.
     */
    Map<Long, CapacidadComprometidaResponse> obtenerCapacidadComprometidaHoy(
            Long excludeRegistroId);
}