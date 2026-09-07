package com.saasa.contingencias.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "vuelo_recursos",
        uniqueConstraints = @UniqueConstraint(columnNames = {"registro_vuelo_diario_id", "proveedor_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VueloRecurso extends EstacionScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vuelo_id", nullable = false)
    private Vuelo vuelo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proveedor_id", nullable = false)
    private Proveedor proveedor;

    /**
     * Referencia al registro diario que habilitó este recurso.
     * Permite asociar recursos a registros específicos del líder.
     * Puede ser null para recursos legacy (antes de la refactorización).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registro_vuelo_diario_id")
    private RegistroVueloDiario registroVueloDiario;

    /**
     * Campos separados para hoteles — coinciden exactamente con el prototipo:
     *   "Habitaciones Simples  → 10"
     *   "Habitaciones Dobles   → 20"
     *   "Habitaciones Matrimoniales → 4"
     *
     * Para proveedores de tipo TRANSPORTE y RESTAURANTE estos campos
     * quedan en null — solo se usa capacidadTotal (transportes/cubiertos).
     */
    @Column(name = "habitaciones_simples")
    private Integer habitacionesSimples;

    @Column(name = "habitaciones_dobles")
    private Integer habitacionesDobles;

    @Column(name = "habitaciones_matrimoniales")
    private Integer habitacionesMatrimoniales;

    /**
     * Campo genérico para TRANSPORTE (unidades/vehículos disponibles)
     * y RESTAURANTE (cubiertos o mesas disponibles).
     * Para HOTEL = suma de los 3 tipos (calculado, no almacenado).
     */
    @Column(name = "capacidad_total")
    private Integer capacidadTotal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "habilitado_por", nullable = false)
    private Usuario habilitadoPor;

    @Column(nullable = false)
    private LocalDateTime habilitadoEn;

    @Column(nullable = false, columnDefinition = "TINYINT DEFAULT 1")
    private Integer estado = 1;

    /**
     * Calcula el total de habitaciones del hotel sumando los 3 tipos.
     * Retorna capacidadTotal directamente si no es hotel.
     */
    @Transient
    public Integer getTotalHabitaciones() {
        int simples       = habitacionesSimples       != null ? habitacionesSimples       : 0;
        int dobles        = habitacionesDobles        != null ? habitacionesDobles        : 0;
        int matrimoniales = habitacionesMatrimoniales != null ? habitacionesMatrimoniales : 0;
        int total = simples + dobles + matrimoniales;
        return total > 0 ? total : capacidadTotal;
    }
}
