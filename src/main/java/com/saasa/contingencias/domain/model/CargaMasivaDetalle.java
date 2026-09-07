package com.saasa.contingencias.domain.model;

import com.saasa.contingencias.domain.enumeration.EstadoDetalleLoteEnum;
import jakarta.persistence.*;
import lombok.*;

/**
 * Una fila (pasajero) dentro de un lote de carga masiva.
 *
 * IMPORTANTE: se guarda {@code atencionId} como Long simple (no como
 * relación @ManyToOne a Atencion) a propósito — este registro se
 * actualiza desde VoucherLoteOrchestratorImpl dentro de un hilo @Async,
 * y usar una relación lazy aquí obligaría a manejar la sesión de
 * Hibernate/proxies entre hilos. Con un Long alcanza: el orquestador
 * solo necesita el ID para llamar a IAtencionVoucherService, que ya
 * recarga la Atencion completa por su cuenta.
 */
@Entity
@Table(name = "carga_masiva_detalles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CargaMasivaDetalle extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lote_id", nullable = false)
    private CargaMasivaLote lote;

    /**
     * NULL hasta que AtencionCargaMasivaCreadorAsyncImpl crea la Atención
     * correspondiente (fase 1). Antes era nullable = false porque la
     * creación era síncrona y la fila solo se guardaba después de tener
     * ya el ID; ahora la fila se guarda primero (POR_CREAR) y el ID llega
     * después, o nunca si la creación falla (ERROR_CREACION).
     */
    @Column(name = "atencion_id")
    private Long atencionId;

    @Column(name = "correlativo", length = 20)
    private String correlativo;

    /**
     * Enlaza a los demás pasajeros del mismo PNR/grupo dentro de este
     * lote (mismo valor que Atencion.grupoId). Null si es un pasajero
     * individual (sin acompañantes).
     */
    @Column(name = "grupo_id", length = 64)
    private String grupoId;

    /**
     * NUEVO — nombre y apellido crudos de la fila del Excel, necesarios
     * para construir el AtencionRequest en la fase 1 (creación async).
     * {@code nombreCompleto} (abajo) se mantiene aparte solo para
     * mostrar en el polling del frontend.
     */
    @Column(length = 100)
    private String nombre;

    @Column(length = 100)
    private String apellido;

    /**
     * NUEVO — datos del servicio de restaurante, solo poblados en la fila
     * titular de cada grupo (esTitular = true). AtencionCargaMasivaCreadorAsyncImpl
     * los usa para construir el ServicioAsignadoRequest una única vez por
     * grupo, igual que hacía antes AtencionCargaMasivaServiceImpl de forma
     * síncrona.
     */
    @Column(name = "pax_restaurante")
    private Integer paxRestaurante;

    private Boolean desayuno;

    private Boolean almuerzo;

    private Boolean cena;

    /**
     * true para la primera fila de cada grupo — es quien recibe el
     * voucher grupal y de quien se toman correo/celular por defecto
     * para los acompañantes que no traen los suyos propios en el Excel.
     */
    @Builder.Default
    @Column(name = "es_titular", nullable = false)
    private Boolean esTitular = false;

    @Column(length = 6)
    private String pnr;

    @Column(name = "nombre_completo", length = 200)
    private String nombreCompleto;

    @Column(length = 150)
    private String correo;

    @Column(length = 20)
    private String celular;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private EstadoDetalleLoteEnum estado = EstadoDetalleLoteEnum.POR_CREAR;

    @Column(name = "mensaje_error", length = 500)
    private String mensajeError;
}
