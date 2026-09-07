package com.saasa.contingencias.domain.model;

import com.saasa.contingencias.domain.enumeration.ContingenciaEnum;
import com.saasa.contingencias.domain.enumeration.EstadoVueloEnum;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

@Entity
@Table(
        name = "vuelos",
        indexes = {
                // Índice único compuesto: garantiza unicidad codigoVuelo+fechaVuelo a nivel BD.
                // Acelera existsByCodigoVueloAndFechaVuelo() y existsByCodigoVueloAndFechaVueloAndIdNot()
                // usados en create(), update(), crearRegistroCompleto() y actualizarRegistroCompleto().
                @Index(name = "uq_vuelo_codigo_fecha",
                        columnList = "codigoVuelo, fechaVuelo",
                        unique = true),
                // Índice en estado para filtros frecuentes (buscar ACTIVO/ANULADO)
                @Index(name = "idx_vuelo_estado", columnList = "estado")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Vuelo extends EstacionScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String aerolinea;

    @Column(nullable = false, length = 20)
    private String codigoVuelo;

    @Column(nullable = false, length = 3)
    private String origen;

    @Column(nullable = false, length = 3)
    private String destino;

    @Column(nullable = false)
    private LocalDate fechaVuelo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContingenciaEnum tipoContingencia;

    @Column(columnDefinition = "TEXT")
    private String observaciones;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EstadoVueloEnum estado = EstadoVueloEnum.ACTIVO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creado_por", nullable = false)
    private Usuario creadoPor;
}
