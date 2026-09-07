package com.saasa.contingencias.domain.model;

import com.saasa.contingencias.domain.enumeration.EstadoAtencionEnum;
import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;
import com.saasa.contingencias.domain.enumeration.OrigenFirmaEnum;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "atenciones")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Atencion extends EstacionScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * MEJORA 1 — Correlativo generado por secuencia MySQL.
     * La secuencia se crea automáticamente por Hibernate con ddl-auto=update.
     * Formato: SGC-000001000, SGC-000001001, ...
     * Se persiste en la columna numero_correlativo como UNIQUE.
     */
    @Column(nullable = false, unique = true, length = 20,updatable = false)
    private String numeroCorrelativo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vuelo_id", nullable = false)
    private Vuelo vuelo;

    /**
     * NUEVO: Referencia al registro diario del vuelo (habilitado por el líder)
     * Conecta la atención con los recursos específicos del día
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registro_vuelo_diario_id")
    private RegistroVueloDiario registroVueloDiario;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Column(nullable = false, length = 100)
    private String apellido;

    @Column(nullable = false, length = 6)
    private String pnr;

    //Cambio en la entidad (Atencion) para el envio grupal de servicios
    @Column(name = "grupo_id", length = 64)
    private String grupoId;

    /**
     * Código de barras escaneado del boarding pass
     */
    @Column(columnDefinition = "TEXT")
    private String codigoBarras;

    /**
     * Fecha de emisión del boarding pass
     */
    @Column(name = "fecha_emision")
    private LocalDate fechaEmision;

    /**
     * Lugar/Aeropuerto de emisión (ej: LIM, MAD)
     */
    @Column(length = 100)
    private String lugarEmision;

    @Column(nullable = false, length = 150)
    private String correo;

    /**
     * TWILIO — Teléfono del pasajero para envío de voucher por WhatsApp.
     * Formato E.164 con código de país. Ej: "+51987654321"
     * Campo opcional: si está vacío/null se omite el envío WhatsApp.
     */
    @Column(length = 20)
    private String telefono;

    @Column(precision = 10, scale = 2)
    private BigDecimal montoTotal = BigDecimal.ZERO;

    @Column(unique = true, length = 50)
    private String codigoAutorizacion;

    @Column(length = 500)
    private String pdfUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EstadoAtencionEnum estado = EstadoAtencionEnum.ACTIVO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "atendido_por", nullable = false)
    private Usuario atendidoPor;

    /**
     * NUEVO — Último usuario que actualizó esta atención (servicios o datos
     * del pasajero). Null si nunca fue modificada tras su creación.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actualizado_por")
    private Usuario actualizadoPor;

    /**
     * NUEVO — Conformidad digital del pasajero.
     * El pasajero dibuja su firma en un canvas dentro del modal de
     * confirmación del agente, aceptando las asignaciones de servicios
     * brindadas, antes de que el voucher sea generado y enviado.
     * Se persiste como imagen PNG en base64 (data URL:
     * "data:image/png;base64,..."), por lo que requiere un tipo de columna
     * de texto largo (LONGTEXT en MySQL) en vez del VARCHAR(150) original,
     * pensado para el nombre tecleado del flujo anterior.
     *
     * Para cargas masivas (origenFirma = AGENTE_LOTE) este campo sigue
     * almacenando el nombre de quien autorizó (texto corto), lo cual sigue
     * siendo compatible con LONGTEXT sin cambios adicionales.
     */
    @Column(name = "firma_pasajero", columnDefinition = "LONGTEXT")
    private String firmaPasajero;

    @Builder.Default
    @Column(name = "firma_conforme", nullable = false, columnDefinition = "TINYINT DEFAULT 0")
    private Boolean firmaConforme = false;

    @Column(name = "firma_fecha")
    private LocalDateTime firmaFecha;

    /**
     * NUEVO — Distingue si firmaPasajero corresponde a la firma real del
     * pasajero (flujo individual) o al nombre de quien autorizó una
     * carga masiva (flujo Excel). Nullable: registros antiguos no lo
     * tienen y se tratan como PASAJERO por compatibilidad.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "origen_firma", length = 20)
    private OrigenFirmaEnum origenFirma;

    /**
     * NUEVO — Rol (RolEnum.name(): ADMINISTRADOR | LIDER_SAASA | AGENTE_SAASA)
     * del usuario que autorizó la carga masiva, solo cuando
     * origenFirma = AGENTE_LOTE. Permite que el reporte muestre
     * dinámicamente "Autorización del administrador/líder/agente
     * (carga masiva)" en vez de un texto fijo. Null cuando origenFirma
     * es PASAJERO (no aplica) o en registros previos a este cambio.
     */
    @Column(name = "origen_firma_rol", length = 20)
    private String origenFirmaRol;

    /**
     * NUEVO — Idioma en el que se genera el voucher PDF de este pasajero
     * (ES/EN). Preferencia persistida: se puede editar desde "Editar datos
     * del pasajero" y se usa cada vez que se genera/regenera el PDF —
     * tanto para el voucher individual como, si el pasajero es titular de
     * un grupo, para el voucher grupal compartido.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "idioma_voucher", nullable = false, length = 5)
    private IdiomaVoucherEnum idiomaVoucher = IdiomaVoucherEnum.ES;
}
