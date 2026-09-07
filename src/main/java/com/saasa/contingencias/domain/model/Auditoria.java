package com.saasa.contingencias.domain.model;

import com.saasa.contingencias.util.DateTimeUtil;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Registro de auditoría del sistema.
 */
@Entity
@Table(name = "auditoria")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Auditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Usuario que ejecutó la acción.
     * Puede ser NULL para acciones del sistema (ej: jobs programados).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    /**
     * Acción ejecutada.
     * Ejemplos: CREAR_ATENCION, ACTUALIZAR_SERVICIOS, ELIMINAR_PROVEEDOR
     */
    @Column(nullable = false, length = 100)
    private String accion;

    /**
     * Módulo del sistema donde ocurrió la acción.
     * Ejemplos: ATENCIONES, USUARIOS, PROVEEDORES, VUELOS
     */
    @Column(nullable = false, length = 50)
    private String modulo;

    /**
     * Dirección IP desde donde se realizó la acción.
     * Puede ser:
     * - IP real del cliente (ej: "192.168.1.100")
     * - "SYSTEM" para acciones automáticas del sistema
     * - NULL si no se pudo determinar
     */
    @Column(name = "ip_origen", length = 45)
    private String ipOrigen;

    /**
     * Información detallada de la operación en formato JSON.
     * Ejemplos:
     * - {"correlativo":"SGC-001","pnr":"ABC123"}
     * - {"nuevoTotal":"838.00","atencionId":1}
     */
    @Column(columnDefinition = "TEXT")
    private String detalle;

    /**
     * Tipo de entidad afectada.
     * Ejemplos: Atencion, Usuario, Proveedor, Vuelo
     */
    @Column(name = "entidad_tipo", length = 50)
    private String entidadTipo;

    /**
     * ID de la entidad afectada.
     * Útil para tracking y filtrado.
     */
    @Column(name = "entidad_id")
    private Long entidadId;

    /**
     * Nombre/correlativo de la entidad para referencia rápida.
     * Ejemplos: "SGC-001", "Juan Pérez", "Hotel Sheraton"
     */
    @Column(name = "entidad_nombre", length = 200)
    private String entidadNombre;

    /**
     * User agent del navegador/cliente.
     * Útil para detectar qué dispositivo/navegador usó el usuario.
     */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "estacion_id")
    private Long estacionId;

    /**
     * Contexto de trabajo (estación+línea aérea) activo del usuario en el
     * momento de la acción auditada — igual que estacionId, se agrega a
     * mano porque Auditoria no extiende EstacionScopedEntity (maneja su
     * propio timestamp `creadoEn`).
     */
    @Column(name = "linea_aerea_id")
    private Long lineaAereaId;

    /**
     * Timestamp de cuándo se creó el registro.
     * Se genera automáticamente.
     */
    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn;

    @PrePersist
    protected void onCreate() {
        creadoEn = DateTimeUtil.ahoraEnLima();
    }
}