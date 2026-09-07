package com.saasa.contingencias.controller;

import com.saasa.contingencias.domain.dto.response.CargaMasivaAtencionResponse;
import com.saasa.contingencias.domain.dto.response.CargaMasivaPreviewResponse;
import com.saasa.contingencias.domain.dto.response.LoteEstadoResponse;
import com.saasa.contingencias.service.IAtencionCargaMasivaCreador;
import com.saasa.contingencias.service.IAtencionCargaMasivaService;
import com.saasa.contingencias.service.IVoucherLoteOrchestrator;
import com.saasa.contingencias.util.ApiResponse;
import com.saasa.contingencias.util.SecurityHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

/**
 * Carga masiva de pasajeros + servicio de RESTAURANTE desde Excel.
 *
 * Separado de AtencionController a propósito, para no tocar ese archivo
 * (muy usado) y mantener esta funcionalidad nueva aislada y fácil de
 * extender luego a HOTEL/TRANSPORTE.
 *
 * DISEÑO ASÍNCRONO: el POST /restaurante crea todo de forma síncrona
 * (rápido) y devuelve un loteId; el envío de vouchers se dispara DESPUÉS,
 * en background, llamando a IVoucherLoteOrchestrator — así la petición
 * HTTP no se queda esperando varios minutos con lotes grandes. El
 * frontend hace polling a GET /lotes/{loteId} para mostrar el progreso.
 */
@RestController
@RequestMapping("/api/v1/atenciones/carga-masiva")
@Tag(name = "Atenciones - Carga masiva")
public class AtencionCargaMasivaController {

    private final IAtencionCargaMasivaService cargaMasivaService;
    private final IAtencionCargaMasivaCreador cargaMasivaCreador;
    private final SecurityHelper securityHelper;

    public AtencionCargaMasivaController(IAtencionCargaMasivaService cargaMasivaService,
                                         IAtencionCargaMasivaCreador cargaMasivaCreador,
                                         SecurityHelper securityHelper) {
        this.cargaMasivaService = cargaMasivaService;
        this.cargaMasivaCreador = cargaMasivaCreador;
        this.securityHelper = securityHelper;
    }

    @GetMapping("/restaurante/plantilla")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Descarga la plantilla Excel para carga masiva de pasajeros + restaurante")
    public ResponseEntity<byte[]> descargarPlantilla() {
        byte[] excel = cargaMasivaService.generarPlantillaRestaurante();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=plantilla-carga-masiva-restaurante.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(excel);
    }

    // NUEVO — previsualiza el Excel (parsea + agrupa por PNR) SIN crear
// nada, para que el modal de confirmación muestre los pasajeros/grupos
// reales (y los errores de validación) antes de que el agente firme.
    @PostMapping(value = "/restaurante/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Previsualiza la carga masiva de restaurante desde Excel (parsea y agrupa " +
            "por PNR sin crear nada), para revisar antes de confirmar.")
    public ResponseEntity<ApiResponse<CargaMasivaPreviewResponse>> previsualizarRestaurante(
            @RequestParam("archivo") MultipartFile archivo,
            @RequestParam("registroVueloDiarioId") Long registroVueloDiarioId,
            @RequestParam("vueloRecursoId") Long vueloRecursoId) {

        CargaMasivaPreviewResponse response = cargaMasivaService.previsualizarRestauranteDesdeExcel(
                archivo, registroVueloDiarioId, vueloRecursoId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping(value = "/restaurante", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Carga masiva de pasajeros + servicio de restaurante desde Excel. " +
            "Crea las atenciones de forma síncrona y devuelve un loteId; el envío de " +
            "vouchers ocurre en background (consultar con GET /lotes/{loteId}).")
    public ResponseEntity<ApiResponse<CargaMasivaAtencionResponse>> cargarRestaurante(
            @RequestParam("archivo") MultipartFile archivo,
            @RequestParam("registroVueloDiarioId") Long registroVueloDiarioId,
            @RequestParam("vueloRecursoId") Long vueloRecursoId,
            // NUEVO — mismos datos del modal de confirmación de la vista individual
            // (correos CC + firma de conformidad), aplicados a TODO el lote.
            @RequestParam(value = "ccDestinos", required = false) List<String> ccDestinos,
            @RequestParam(value = "firmaPasajero", required = false) String firmaPasajero,
            @RequestParam(value = "idempotencyKey", required = false) String idempotencyKey,
            @AuthenticationPrincipal UserDetails user) {

        Long usuarioId = securityHelper.getUsuarioId(user);

        Optional<CargaMasivaAtencionResponse> existente =
                cargaMasivaService.buscarLotePorIdempotencyKey(idempotencyKey);
        if (existente.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(existente.get()));
        }

        CargaMasivaAtencionResponse response = cargaMasivaService.cargarRestauranteDesdeExcel(
                archivo, registroVueloDiarioId, vueloRecursoId,ccDestinos,firmaPasajero, usuarioId,idempotencyKey);

        // La transacción de arriba ya hizo commit al llegar aquí (el proxy de
        // @Transactional de cargarRestauranteDesdeExcel confirma al retornar).
        // Recién ahora es seguro disparar la fase 1 en background: el hilo
        // @Async ya puede ver el lote y sus filas recién guardados. Esta
        // fase, al terminar, dispara ella misma el envío de vouchers (fase 2)
        // — ver AtencionCargaMasivaCreadorAsyncImpl.
        cargaMasivaCreador.crearAtencionesAsync(response.loteId(), usuarioId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/lotes/{loteId}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Consulta el progreso de envío de vouchers de un lote (polling)")
    public ResponseEntity<ApiResponse<LoteEstadoResponse>> consultarLote(@PathVariable String loteId) {
        return ResponseEntity.ok(ApiResponse.success(cargaMasivaService.consultarEstadoLote(loteId)));
    }
}
