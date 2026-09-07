package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.*;
import com.saasa.contingencias.domain.dto.response.*;
import org.springframework.data.domain.*;
import java.util.List;
import java.util.Map;
//Hola
public interface IAtencionService {
    Page<AtencionResponse> findAll(Pageable pageable, String rolUsuario, Long usuarioId, Long proveedorId);
    AtencionResponse findById(Long id);
    AtencionResponse create(AtencionRequest request, Long usuarioId);
    List<AtencionResponse> createBatch(AtencionBatchRequest request, Long usuarioId);
    AtencionResponse update(Long id, AtencionRequest request);
    void anular(Long id, Long usuarioId);
    void restaurar(Long id, Long usuarioId);
    List<ServicioAsignadoResponse> asignarServicios(Long atencionId, List<ServicioAsignadoRequest> servicios, Long usuarioId);

    /**
     * Busca todas las atenciones asociadas a un registro diario específico
     */
    List<AtencionResponse> findByRegistroVueloDiarioId(Long registroVueloDiarioId);

    /**
     * Verifica si un PNR ya tiene una atención activa para el vuelo indicado.
     */
    PnrVerificacionResponse verificarPnr(String pnr, Long vueloId);

}
