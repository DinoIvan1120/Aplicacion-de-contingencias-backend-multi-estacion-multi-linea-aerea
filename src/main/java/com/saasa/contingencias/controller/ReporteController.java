package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.dto.request.ActualizarPasajeroRequest;
import com.saasa.contingencias.domain.dto.request.ActualizarServiciosRequest;
import com.saasa.contingencias.domain.dto.request.EnvioEmailRequest;
import com.saasa.contingencias.domain.dto.request.ReporteFilterRequest;
import com.saasa.contingencias.domain.dto.response.ReporteDetalleResponse;
import com.saasa.contingencias.domain.dto.response.ReporteVoucherResponse;
import com.saasa.contingencias.domain.dto.response.ResumenReporteResponse;
import com.saasa.contingencias.service.IReporteService;
import com.saasa.contingencias.util.ApiResponse;
import com.saasa.contingencias.util.SecurityHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reportes")
@Tag(name = "Reportes")
public class ReporteController {

    private final IReporteService reporteService;
    private final SecurityHelper securityHelper;

    public ReporteController(IReporteService reporteService, SecurityHelper securityHelper) {
        this.reporteService = reporteService;
        this.securityHelper = securityHelper;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA','LINEA_AEREA','PROVEEDOR')")
    @Operation(summary = "Lista atenciones filtradas según el rol del usuario autenticado")
    public ResponseEntity<ApiResponse<Page<ReporteVoucherResponse>>> findAll(
            @RequestParam(required = false) String correlativo,
            @RequestParam(required = false) String pnr,
            @RequestParam(required = false) String nombrePasajero,
            @RequestParam(required = false) Long vueloId,
            @RequestParam(required = false) Long hotelId,
            @RequestParam(required = false) Long transporteId,
            @RequestParam(required = false) Long restauranteId,
            @RequestParam(required = false) Long agenteId,
            @RequestParam(required = false) LocalDate fechaDesde,
            @RequestParam(required = false) LocalDate fechaHasta,
            @RequestParam(required = false) String estado,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserDetails user) {

        ReporteFilterRequest filtros = new ReporteFilterRequest(
                correlativo, pnr, nombrePasajero,
                vueloId, hotelId, transporteId, restauranteId, agenteId,
                fechaDesde, fechaHasta,estado);

        return ResponseEntity.ok(ApiResponse.success(
                reporteService.findAll(filtros, securityHelper.getRol(user),
                        securityHelper.getUsuarioId(user), null, pageable)));
    }

    @GetMapping("/excel")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA','LINEA_AEREA','PROVEEDOR')")
    @Operation(summary = "Exporta atenciones a Excel (.xlsx). Máximo 10.000 registros.")
    public ResponseEntity<byte[]> exportarExcel(
            @RequestParam(required = false) String correlativo,
            @RequestParam(required = false) String pnr,
            @RequestParam(required = false) String nombrePasajero,
            @RequestParam(required = false) Long vueloId,
            @RequestParam(required = false) Long agenteId,
            @RequestParam(required = false) LocalDate fechaDesde,
            @RequestParam(required = false) LocalDate fechaHasta,
            @RequestParam(defaultValue = "true") boolean incluirAnulados,
            @AuthenticationPrincipal UserDetails user) {

        ReporteFilterRequest filtros = new ReporteFilterRequest(
                correlativo, pnr, nombrePasajero,
                vueloId, null, null, null, agenteId, fechaDesde, fechaHasta,
                incluirAnulados ? null: "ACTIVO");

        byte[] excel = reporteService.exportarExcel(filtros, securityHelper.getRol(user),
                securityHelper.getUsuarioId(user), null);

        String filename = "reporte-atenciones-" + LocalDate.now() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA','LINEA_AEREA','PROVEEDOR')")
    @Operation(summary = "Obtiene el detalle completo de un reporte/voucher por su ID de atención")
    public ResponseEntity<ApiResponse<ReporteDetalleResponse>> getDetalleById(@PathVariable Long id, @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(ApiResponse.success(
                "Detalle del reporte obtenido exitosamente",
                reporteService.findDetalleByAtencionId(id,securityHelper.getRol(user),securityHelper.getUsuarioId(user))));
    }

    @GetMapping("/correlativo/{correlativo}")
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA','LINEA_AEREA','PROVEEDOR')")
    @Operation(summary = "Obtiene el detalle completo de un reporte/voucher por su correlativo")
    public ResponseEntity<ApiResponse<ReporteDetalleResponse>> getDetalleByCorrelativo(
            @PathVariable String correlativo, @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(ApiResponse.success(
                "Detalle del reporte obtenido exitosamente",
                reporteService.findByCorrelativo(correlativo,securityHelper.getRol(user), securityHelper.getUsuarioId(user))));
    }

    @PutMapping("/{id}/servicios")
    @Transactional
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Actualiza los servicios asignados de un voucher")
    public ResponseEntity<ApiResponse<ReporteDetalleResponse>> actualizarServicios(
            @PathVariable Long id,
            @Valid @RequestBody ActualizarServiciosRequest request,
            @AuthenticationPrincipal UserDetails user) {
        ReporteDetalleResponse detalle = reporteService.actualizarServicios(
                id, request, securityHelper.getUsuarioId(user));
        return ResponseEntity.ok(ApiResponse.success("Servicios actualizados exitosamente", detalle));
    }

    @PatchMapping("/{id}/pasajero")
    @Transactional
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Actualiza los datos del pasajero (nombre, correo, teléfono WhatsApp y PNR). Solo Administrador y Líder SAASA.")
    public ResponseEntity<ApiResponse<ReporteDetalleResponse>> actualizarPasajero(
            @PathVariable Long id,
            @Valid @RequestBody ActualizarPasajeroRequest request,
            @AuthenticationPrincipal UserDetails user) {
        ReporteDetalleResponse detalle = reporteService.actualizarPasajero(
                id, request, securityHelper.getUsuarioId(user));
        return ResponseEntity.ok(ApiResponse.success("Datos del pasajero actualizados exitosamente", detalle));
    }

    @PostMapping("/{id}/regenerar-pdf")
    @Transactional
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Regenera el PDF actualizado y lo envía por email (y WhatsApp opcional)")
    public ResponseEntity<ApiResponse<Map<String, String>>> regenerarPdf(
            @PathVariable Long id,
            @Valid @RequestBody EnvioEmailRequest request,
            @AuthenticationPrincipal UserDetails user) {
        String pdfUrl = reporteService.regenerarYEnviarPdf(
                id, securityHelper.getUsuarioId(user),
                request.correoDestino(), request.telefono(),request.idiomaVoucher());
        return ResponseEntity.ok(ApiResponse.success(
                "PDF regenerado y enviado exitosamente", Map.of("pdfUrl", pdfUrl)));
    }

    @PostMapping("/{id}/descargar-actualizado")
    @Transactional
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Regenera PDF actualizado y devuelve URL de descarga sin enviar email")
    public ResponseEntity<ApiResponse<Map<String, String>>> descargarActualizado(@PathVariable Long id) {
        try {
            String urlFirmada = reporteService.regenerarPdfSoloDescarga(id);
            return ResponseEntity.ok(ApiResponse.success(
                    "PDF generado correctamente", Map.of("downloadUrl", urlFirmada)));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Error al generar PDF: " + e.getMessage()));
        }
    }

    @GetMapping("/resumen")
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA','LINEA_AEREA','PROVEEDOR')")
    @Operation(summary = "KPIs + datos para gráficos: atenciones/día, importe/día y distribución por tipo de servicio")
    public ResponseEntity<ApiResponse<ResumenReporteResponse>> getResumen(
            @RequestParam(required = false) LocalDate fechaDesde,
            @RequestParam(required = false) LocalDate fechaHasta,
            @AuthenticationPrincipal UserDetails user) {
        ResumenReporteResponse resumen = reporteService.getResumen(
                fechaDesde, fechaHasta, securityHelper.getRol(user), securityHelper.getUsuarioId(user));
        return ResponseEntity.ok(ApiResponse.success("Resumen generado exitosamente", resumen));
    }

    @PatchMapping("/correlativo/{correlativo}/anular")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Anula un reporte por su correlativo (agente solo puede anular los suyos)")
    public ResponseEntity<ApiResponse<Void>> anularPorCorrelativo(
            @PathVariable String correlativo, @AuthenticationPrincipal UserDetails user) {
        reporteService.anularPorCorrelativo(correlativo, securityHelper.getUsuarioId(user));
        return ResponseEntity.ok(ApiResponse.success("Reporte anulado exitosamente", null));
    }

    @PatchMapping("/correlativo/{correlativo}/restaurar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Restaura (revierte anulación) un reporte por su correlativo")
    public ResponseEntity<ApiResponse<Void>> restaurarPorCorrelativo(
            @PathVariable String correlativo, @AuthenticationPrincipal UserDetails user) {
        reporteService.restaurarPorCorrelativo(correlativo, securityHelper.getUsuarioId(user));
        return ResponseEntity.ok(ApiResponse.success("Reporte restaurado exitosamente", null));
    }
}
