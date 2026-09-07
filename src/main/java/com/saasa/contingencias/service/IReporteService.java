package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.ActualizarPasajeroRequest;
import com.saasa.contingencias.domain.dto.request.ActualizarServiciosRequest;
import com.saasa.contingencias.domain.dto.request.ReporteFilterRequest;
import com.saasa.contingencias.domain.dto.response.ReporteDetalleResponse;
import com.saasa.contingencias.domain.dto.response.ReporteVoucherResponse;
import com.saasa.contingencias.domain.dto.response.ResumenReporteResponse;
import org.springframework.data.domain.*;

import java.time.LocalDate;

public interface IReporteService {
    Page<ReporteVoucherResponse> findAll(ReporteFilterRequest filtros, String rolUsuario, Long usuarioId, Long proveedorId, Pageable pageable);
    byte[] exportarExcel(ReporteFilterRequest filtros, String rolUsuario, Long usuarioId, Long proveedorId);


    // ═══════════════════════════════════════════════════════════════════════════════
    // NUEVOS MÉTODOS PARA DETALLE Y EDICIÓN DE REPORTES
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Obtiene el detalle completo de un reporte/voucher por su correlativo.
     */
    //ReporteDetalleResponse findByCorrelativo(String correlativo);

    /**
     * Obtiene el detalle completo de un reporte/voucher por su ID de atención.
     */
    //ReporteDetalleResponse findDetalleByAtencionId(Long atencionId);

    /**
     * Actualiza los servicios asignados a un voucher.
     */
    ReporteDetalleResponse actualizarServicios(Long atencionId,
                                               ActualizarServiciosRequest request,
                                               Long usuarioId);

    /**
     * Actualiza los datos del pasajero de un voucher (nombre, apellido,
     * correo, teléfono WhatsApp y/o PNR). Restringido a ADMINISTRADOR y LIDER_SAASA.
     */
    ReporteDetalleResponse actualizarPasajero(Long atencionId,
                                              ActualizarPasajeroRequest request,
                                              Long usuarioId);

    /**
     * Regenera el PDF de un voucher con la información actualizada,
     * lo envía por email al correoDestino indicado y opcionalmente
     * por WhatsApp si se proporciona teléfono.
     *
     * @param atencionId     ID de la atención
     * @param usuarioId      ID del usuario que dispara la acción (auditoría)
     * @param correoDestino  Correo al que se enviará el PDF (puede diferir del registrado)
     * @param telefono       Teléfono WhatsApp opcional (null = omitir envío WhatsApp)
     */
    String regenerarYEnviarPdf(Long atencionId, Long usuarioId, String correoDestino, String telefono,String idiomaVoucher);

    /**
     * Regenera el PDF con servicios actuales y devuelve URL firmada para descarga
     * directa, SIN enviar email al pasajero.
     */
    String regenerarPdfSoloDescarga(Long atencionId);

    /**
     * Genera el resumen con KPIs y datos para los tres gráficos del módulo de reportes.
     *
     * @param fechaDesde inicio del rango (null = último mes)
     * @param fechaHasta fin del rango    (null = hoy)
     * @param rolUsuario rol del usuario autenticado (para filtrado futuro)
     * @param usuarioId  ID del usuario autenticado
     */
    ResumenReporteResponse getResumen(LocalDate fechaDesde,
                                      LocalDate fechaHasta,
                                      String rolUsuario,
                                      Long usuarioId);

    void anularPorCorrelativo(String correlativo, Long usuarioId);
    void restaurarPorCorrelativo(String correlativo, Long usuarioId);

    ReporteDetalleResponse findByCorrelativo(String correlativo, String rolUsuario, Long usuarioId);
    ReporteDetalleResponse findDetalleByAtencionId(Long atencionId, String rolUsuario, Long usuarioId);
}
