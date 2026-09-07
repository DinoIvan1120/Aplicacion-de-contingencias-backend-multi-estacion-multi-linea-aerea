package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.response.CargaMasivaAtencionResponse;
import com.saasa.contingencias.domain.dto.response.CargaMasivaPreviewResponse;
import com.saasa.contingencias.domain.dto.response.LoteEstadoResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

/**
 * Carga masiva de pasajeros + servicio de RESTAURANTE desde una plantilla
 * Excel (nombre, apellido, PNR, correo, celular opcional, cantidad de pax
 * y comidas), con agrupación automática por PNR (titular + acompañantes).
 */
public interface IAtencionCargaMasivaService {

    /** Genera la plantilla .xlsx que el agente debe llenar y volver a subir. */
    byte[] generarPlantillaRestaurante();

    /**
     * @param archivo                Excel .xlsx con la plantilla llena
     * @param registroVueloDiarioId  Registro diario del vuelo habilitado hoy
     * @param vueloRecursoId         Recurso de restaurante habilitado para ese registro
     * @param ccDestinos             NUEVO — correos CC (aerolínea/proveedores) elegidos en el
     *                               modal de confirmación previo a la carga; se aplican a TODOS
     *                               los vouchers del lote.
     * @param firmaPasajero          NUEVO — firma digital de conformidad (nombre completo)
     *                               ingresada en el mismo modal; se aplica a TODAS las atenciones
     *                               creadas por el lote.
     * @param usuarioId              Agente que realiza la carga (auditoría)
     */
    CargaMasivaAtencionResponse cargarRestauranteDesdeExcel(
            MultipartFile archivo,
            Long registroVueloDiarioId,
            Long vueloRecursoId,
            List<String> ccDestinos,
            String firmaPasajero,
            Long usuarioId,
            String idempotencyKey);

    /**
     * NUEVO — si ya existe un lote con esta clave (reintento del agente),
     * lo devuelve tal cual. El controller debe llamar esto ANTES de
     * cargarRestauranteDesdeExcel y, si devuelve algo, responder con eso
     * SIN volver a disparar la fase de creación.
     */
    Optional<CargaMasivaAtencionResponse> buscarLotePorIdempotencyKey(String idempotencyKey);

    /** Estado consultable (polling) del envío de vouchers de un lote. */
    LoteEstadoResponse consultarEstadoLote(String loteId);

    /**
     * NUEVO — previsualiza el Excel SIN crear nada: parsea y agrupa por PNR
     * con las mismas reglas que {@link #cargarRestauranteDesdeExcel}, para
     * que el modal de confirmación muestre a los pasajeros/grupos reales
     * (y los errores de validación fila por fila) antes de que el agente
     * firme y confirme la carga.
     *
     * @param archivo                Excel .xlsx con la plantilla llena
     * @param registroVueloDiarioId  Registro diario del vuelo habilitado hoy
     * @param vueloRecursoId         Recurso de restaurante habilitado para ese registro
     */
    CargaMasivaPreviewResponse previsualizarRestauranteDesdeExcel(
            MultipartFile archivo,
            Long registroVueloDiarioId,
            Long vueloRecursoId);
}
