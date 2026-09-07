package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.*;
import com.saasa.contingencias.domain.dto.response.*;
import com.saasa.contingencias.domain.enumeration.ContingenciaEnum;
import com.saasa.contingencias.domain.enumeration.EstadoVueloEnum;
import org.springframework.data.domain.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface IVueloService {
    Page<VueloResponse> findAll(Pageable pageable);

    /**
     * Búsqueda dinámica con filtros opcionales (Specification).
     * Parámetros null → ignorados. Igual que /usuarios/buscar.
     */
    Page<VueloResponse> buscar(String aerolinea, String codigoVuelo, String origen,
                               String destino, ContingenciaEnum tipoContingencia,
                               EstadoVueloEnum estado, Pageable pageable);

    VueloResponse create(VueloRequest request, Long usuarioId);
    VueloResponse update(Long id, VueloRequest request);
    void anular(Long id);

    /** Reactiva un vuelo previamente ANULADO → lo vuelve a ACTIVO. */
    void habilitar(Long id);
    List<VueloRecursoResponse> findRecursos(Long vueloId);
    VueloRecursoResponse habilitarRecurso(Long vueloId, VueloRecursoRequest request, Long usuarioId);
    void deshabilitarRecurso(Long vueloId, Long recursoId);

    /**
     * Carga masiva desde Excel.
     * Retorna CargaMasivaResponse con los vuelos creados y los errores
     * por fila, en lugar de silenciarlos en el log.
     */
    CargaMasivaResponse cargarDesdeExcel(MultipartFile archivo, Long usuarioId, Long estacionId, Long lineaAereaId);

    /**
     * Devuelve los vuelos ACTIVOS cuya fechaVuelo = hoy en Lima (UTC-5).
     * Alimenta el combo del itinerario del líder. El cálculo de "hoy"
     * ocurre en el servidor con DateTimeUtil.hoyEnLima() para garantizar
     * consistencia independientemente de la zona horaria del cliente.
     */
    List<VueloResponse> obtenerItinerarioHoy();


    // ── Operaciones unificadas para la vista del Líder (NUEVAS) ──────────────

    /**
     * CREAR: registra el vuelo + habilita todos sus recursos en una sola transacción.
     * Corresponde a cuando el Líder llena el formulario completo por primera vez
     * y presiona "Guardar Información de Vuelo" (imagen 8/9).
     */
    RegistroVueloResponse crearRegistroCompleto(RegistroVueloRequest request, Long usuarioId);

    /**
     * OBTENER: devuelve el vuelo + todos sus recursos habilitados.
     * Permite al frontend cargar la vista completa del Líder con una sola llamada.
     * También usado para mostrar el "Resumen de Recursos Habilitados" (imagen 9).
     */
    RegistroVueloResponse obtenerRegistroCompleto(Long vueloId);

    /**
     * ACTUALIZAR: modifica el vuelo y sincroniza sus recursos en una sola transacción.
     * Upsert por proveedorId: crea los nuevos, actualiza los existentes.
     * Los recursos que NO vienen en la lista se DESACTIVAN.
     * Corresponde al botón "Guardar Información de Vuelo" cuando ya existe el vuelo.
     */
    RegistroVueloResponse actualizarRegistroCompleto(Long vueloId,
                                                     RegistroVueloRequest request,
                                                     Long usuarioId);
}