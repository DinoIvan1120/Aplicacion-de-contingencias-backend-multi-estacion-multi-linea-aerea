package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.dto.request.*;
import com.saasa.contingencias.domain.dto.response.*;
import com.saasa.contingencias.service.*;
import com.saasa.contingencias.service.impl.BoardingPassImageService;
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
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/atenciones")
@Tag(name = "Atenciones")
public class AtencionController {

    private final IAtencionService atencionService;
    private final IAtencionVoucherService atencionVoucherService; // ← nuevo
    private final IBoardingPassService boardingPassService;
    private final BoardingPassImageService boardingPassImageService;
    private final SecurityHelper securityHelper;

    public AtencionController(IAtencionService atencionService,
                              IBoardingPassService boardingPassService,
                              BoardingPassImageService boardingPassImageService,
                              SecurityHelper securityHelper,
                              IAtencionVoucherService atencionVoucherService) {
        this.atencionService = atencionService;
        this.boardingPassService = boardingPassService;
        this.boardingPassImageService = boardingPassImageService;
        this.securityHelper = securityHelper;
        this.atencionVoucherService = atencionVoucherService;

    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA','LINEA_AEREA','PROVEEDOR')")
    @Operation(summary = "Lista atenciones filtradas según el rol del usuario autenticado")
    public ResponseEntity<ApiResponse<Page<AtencionResponse>>> findAll(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(ApiResponse.success(
                atencionService.findAll(pageable, securityHelper.getRol(user),
                        securityHelper.getUsuarioId(user), null)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AtencionResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(atencionService.findById(id)));
    }

    @GetMapping("/verificar-pnr")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Verificar si un PNR ya está registrado en el vuelo")
    public ResponseEntity<ApiResponse<PnrVerificacionResponse>> verificarPnr(
            @RequestParam String pnr,
            @RequestParam Long vueloId) {
        return ResponseEntity.ok(ApiResponse.success(atencionService.verificarPnr(pnr, vueloId)));
    }

    @PostMapping("/escanear-boarding-pass")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Escanear código de barras del boarding pass")
    public ResponseEntity<ApiResponse<BoardingPassScanResponse>> escanearBoardingPass(
            @Valid @RequestBody BoardingPassScanRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Boarding pass escaneado exitosamente",
                boardingPassService.escanearBoardingPass(request)));
    }

    @PostMapping(value = "/escanear-imagen", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Procesar imagen de boarding pass y extraer código de barras automáticamente")
    public ResponseEntity<ApiResponse<BoardingPassScanResponse>> escanearImagen(
            @RequestParam("imagen") org.springframework.web.multipart.MultipartFile imagen) {
        return ResponseEntity.ok(ApiResponse.success(
                "Imagen procesada exitosamente",
                boardingPassImageService.procesarImagen(imagen)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    public ResponseEntity<ApiResponse<AtencionResponse>> create(
            @Valid @RequestBody AtencionRequest request,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        atencionService.create(request, securityHelper.getUsuarioId(user))));
    }

    @PostMapping("/batch")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','AGENTE_SAASA','LIDER_SAASA')")
    public ResponseEntity<ApiResponse<List<AtencionResponse>>> createBatch(
            @Valid @RequestBody AtencionBatchRequest request,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        atencionService.createBatch(request, securityHelper.getUsuarioId(user))));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    public ResponseEntity<ApiResponse<AtencionResponse>> update(
            @PathVariable Long id, @Valid @RequestBody AtencionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(atencionService.update(id, request)));
    }

    @PatchMapping("/{id}/anular")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    public ResponseEntity<ApiResponse<Void>> anular(
            @PathVariable Long id, @AuthenticationPrincipal UserDetails user) {
        atencionService.anular(id, securityHelper.getUsuarioId(user));
        return ResponseEntity.ok(ApiResponse.success("Atención anulada exitosamente", null));
    }

    @PatchMapping("/{id}/restaurar") // ← NUEVO
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    public ResponseEntity<ApiResponse<Void>> restaurar(
            @PathVariable Long id, @AuthenticationPrincipal UserDetails user) {
        atencionService.restaurar(id, securityHelper.getUsuarioId(user));
        return ResponseEntity.ok(ApiResponse.success("Atención restaurada exitosamente", null));
    }

    @PostMapping("/{id}/servicios")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','AGENTE_SAASA')")
    public ResponseEntity<ApiResponse<List<ServicioAsignadoResponse>>> asignarServicios(
            @PathVariable Long id,
            @Valid @RequestBody List<ServicioAsignadoRequest> servicios,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(ApiResponse.success(
                atencionService.asignarServicios(id, servicios, securityHelper.getUsuarioId(user))));
    }

    @PostMapping("/{id}/pdf")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Genera voucher PDF, lo sube a S3 y lo envía por email al pasajero")
    public ResponseEntity<ApiResponse<String>> generarPdf(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails user) {
        String url = atencionVoucherService.generarYEnviarVoucherLegacy(id, securityHelper.getUsuarioId(user));
        return ResponseEntity.ok(ApiResponse.success("PDF generado y enviado al pasajero", url));
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Descarga el voucher PDF de una atención desde S3")
    public ResponseEntity<byte[]> descargarPdf(@PathVariable Long id) {
        byte[] pdf = atencionVoucherService.descargarPdf(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=voucher-" + id + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping("/{id}/voucher/descargar")
    @Operation(summary = "Genera URL firmada temporal (15 min) para descargar el voucher")
    public ResponseEntity<Map<String, String>> descargarVoucher(@PathVariable Long id) {
        return ResponseEntity.ok(atencionVoucherService.urlFirmadaVoucher(id));
    }

    @PostMapping("/{id}/enviar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA','LINEA_AEREA')")
    public ResponseEntity<ApiResponse<Void>> reenviarPdf(
            @PathVariable Long id,
            @Valid @RequestBody EnvioEmailRequest request,
            @AuthenticationPrincipal UserDetails user) {
        atencionVoucherService.reenviarPdf(id, request, securityHelper.getUsuarioId(user));
        return ResponseEntity.ok(ApiResponse.success("PDF reenviado exitosamente", null));
    }

    @PostMapping("/{id}/voucher/generar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Generar PDF de voucher de servicios y subirlo a S3")
    public ResponseEntity<ApiResponse<VoucherResponse>> generarVoucherPdf(
            @PathVariable Long id, @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(ApiResponse.success(
                "Voucher PDF generado exitosamente",
                atencionVoucherService.generarVoucherPdf(id)));
    }

    @PostMapping("/{id}/voucher/generar-y-enviar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Generar PDF de voucher y enviar por email y WhatsApp")
    public ResponseEntity<ApiResponse<VoucherResponse>> generarYEnviarVoucher(
            @PathVariable Long id,
            @Valid @RequestBody GenerarVoucherRequest request,
            @AuthenticationPrincipal UserDetails user) {
        VoucherResponse response = atencionVoucherService.generarYEnviarVoucher(
                id, request, securityHelper.getUsuarioId(user));
        String correo = request.correoDestino() != null ? request.correoDestino() : "pasajero";
        return ResponseEntity.ok(ApiResponse.success(
                "Voucher generado y enviado a " + correo, response));
    }

    @PostMapping("/voucher-grupal/generar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Generar UN solo voucher PDF para varios pasajeros que comparten PNR/correo (sin enviar)")
    public ResponseEntity<ApiResponse<VoucherGrupalResponse>> generarVoucherGrupalPdf(
            @Valid @RequestBody VoucherGrupalRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Voucher grupal generado exitosamente",
                atencionVoucherService.generarVoucherGrupalPdf(request)));
    }

    @PostMapping("/voucher-grupal/generar-y-enviar")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Generar UN solo voucher PDF y enviarlo en UN solo correo a varios pasajeros que comparten PNR/correo")
    public ResponseEntity<ApiResponse<VoucherGrupalResponse>> generarYEnviarVoucherGrupal(
            @Valid @RequestBody VoucherGrupalRequest request,
            @AuthenticationPrincipal UserDetails user) {
        VoucherGrupalResponse response = atencionVoucherService.generarYEnviarVoucherGrupal(
                request, securityHelper.getUsuarioId(user));
        String correo = request.correoDestino() != null ? request.correoDestino() : "pasajero";
        return ResponseEntity.ok(ApiResponse.success(
                "Voucher grupal generado y enviado a " + correo, response));
    }
}
