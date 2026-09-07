package com.saasa.contingencias.domain.model;

import com.saasa.contingencias.domain.enumeration.EstadoLoteEnum;
import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import jakarta.persistence.*;
import lombok.*;

/**
 * Cabecera de un lote de carga masiva de pasajeros + servicios desde Excel.
 *
 * FLUJO:
 * 1. AtencionCargaMasivaServiceImpl parsea el Excel, crea las Atenciones y
 *    sus ServicioAsignado de forma SÍNCRONA, y guarda este lote con
 *    estado PROCESANDO.
 * 2. VoucherLoteOrchestratorImpl procesa el envío de vouchers (PDF + email
 *    + WhatsApp) en background (@Async) y va actualizando procesados/
 *    exitosos/fallidos a medida que avanza.
 * 3. El frontend consulta este lote por su {@code loteId} (polling) para
 *    mostrar una barra de progreso "X de Y enviados".
 */
@Entity
@Table(name = "carga_masiva_lotes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CargaMasivaLote extends AuditableEntity {

    /**
     * NUEVO — separador usado para serializar/deserializar {@link #ccDestinosJson}
     * (no es JSON real, se mantiene el nombre del campo por claridad con el
     * modal "correosCc" del frontend). Compartido entre
     * AtencionCargaMasivaServiceImpl (serializa) y VoucherLoteOrchestratorImpl
     * (deserializa) para no duplicar el literal en dos clases.
     */
    public static final String CC_SEPARATOR = ";;";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Identificador público del lote (UUID), el que usa el frontend para
     * hacer polling. Se usa un UUID en vez del ID numérico para no exponer
     * el correlativo interno de la tabla.
     */
    @Column(name = "lote_id", nullable = false, unique = true, length = 40)
    private String loteId;

    /**
     * Tipo de servicio cargado masivamente. Por ahora solo RESTAURANTE;
     * el modelo queda listo para HOTEL/TRANSPORTE sin cambios de esquema.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_servicio", nullable = false, length = 20)
    private TipoDetalleEnum tipoServicio;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vuelo_id", nullable = false)
    private Vuelo vuelo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registro_vuelo_diario_id", nullable = false)
    private RegistroVueloDiario registroVueloDiario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vuelo_recurso_id", nullable = false)
    private VueloRecurso vueloRecurso;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cargado_por", nullable = false)
    private Usuario cargadoPor;

    @Column(name = "total_pasajeros", nullable = false)
    private Integer totalPasajeros;

    @Column(name = "total_grupos", nullable = false)
    private Integer totalGrupos;

    @Builder.Default
    @Column(nullable = false)
    private Integer procesados = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer exitosos = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer fallidos = 0;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private EstadoLoteEnum estado = EstadoLoteEnum.CREANDO_ATENCIONES;

    /**
     * Errores de VALIDACIÓN de filas del Excel (formato, PNR inválido,
     * grupo sin correo de titular, etc.) detectados ANTES de crear
     * ninguna atención — serializados como JSON simple (lista de strings).
     * No confundir con errores de ENVÍO de voucher, que se guardan por
     * fila en CargaMasivaDetalle.mensajeError.
     */
    @Column(name = "errores_validacion", columnDefinition = "TEXT")
    private String erroresValidacionJson;

    /**
     * NUEVO — correos CC (aerolínea/proveedores) elegidos por el agente en
     * el modal de confirmación previo a la carga masiva, serializados
     * como texto delimitado por CC_SEPARATOR. Replica el mismo
     * "correosCc" del modal de confirmación de la vista individual del
     * agente y se aplica a TODOS los vouchers del lote
     * (VoucherLoteOrchestratorImpl los parsea y los pasa a cada
     * GenerarVoucherRequest/VoucherGrupalRequest).
     */
    @Column(name = "cc_destinos_json", columnDefinition = "TEXT")
    private String ccDestinosJson;

    /**
     * NUEVO — firma digital de conformidad (nombre completo) ingresada
     * por el agente en el mismo modal previo a la carga masiva. Al ser
     * una carga masiva no hay una firma por pasajero: este único texto
     * de conformidad se aplica a todas las atenciones creadas por el
     * lote.
     */
    @Column(name = "firma_pasajero", length = 255)
    private String firmaPasajero;

    /**
     * NUEVO — clave de idempotencia generada por el frontend al seleccionar
     * el archivo. Si el agente reintenta el envío con la MISMA clave, el
     * backend devuelve el lote existente en vez de crear uno duplicado.
     */
    @Column(name = "idempotency_key", unique = true, length = 64)
    private String idempotencyKey;
}
