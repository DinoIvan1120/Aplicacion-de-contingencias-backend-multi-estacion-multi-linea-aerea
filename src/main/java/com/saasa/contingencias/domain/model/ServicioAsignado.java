package com.saasa.contingencias.domain.model;

import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "servicios_asignados")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ServicioAsignado extends EstacionScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "atencion_id", nullable = false)
    private Atencion atencion;

    /*
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "servicio_proveedor_id")
    private ServicioProveedor servicioProveedor;
    */


    /**
     * NUEVO: Referencia al recurso habilitado del día (hotel/transporte/restaurante específico)
     * Permite conectar el servicio con la disponibilidad del registro diario
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vuelo_recurso_id")
    private VueloRecurso vueloRecurso;

    /**
     * Tipo de habitación para servicios de hotel (SIMPLE, DOBLE, MATRIMONIAL)
     */
    @Column(length = 20)
    private String tipoHabitacion;

    /**
     * Tipo de transporte (INDIVIDUAL, GRUPAL)
     * Solo aplica cuando tipoDetalle == TRANSPORTE
     */
    @Column(length = 20)
    private String tipoTransporte;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TipoDetalleEnum tipoDetalle;

    @Column(nullable = false)
    private Integer cantidad = 1;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal montoUnitario;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal montoSubtotal;

    @Column(nullable = false)
    private LocalDateTime asignadoEn;

    /**
     * Servicios adicionales (checkboxes del formulario)
     */
    @Builder.Default
    @Column(nullable = false)
    private Boolean desayuno = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean almuerzo = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean cena = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean snack = false;

    /** Fecha de ingreso al hotel (check-in) — solo aplica para HOTEL */
    @Column(name = "fecha_ingreso")
    private LocalDate fechaIngreso;

    /** Fecha de salida del hotel (check-out) — solo aplica para HOTEL */
    @Column(name = "fecha_salida")
    private LocalDate fechaSalida;
}
