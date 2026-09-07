package com.saasa.contingencias.domain.model;


import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Registro diario de vuelos del itinerario.
 *
 * Esta tabla NO almacena vuelos, sino REGISTROS de activación diaria.
 * Permite que:
 *   - El administrador cargue vuelos masivamente en tabla `vuelos` (itinerario base)
 *   - El líder SELECCIONE vuelos del itinerario y los registre para el día con recursos
 *   - Cada registro tenga trazabilidad: quién, cuándo, qué vuelo, qué recursos
 *   - Los agentes vean solo los registros del día actual
 *   - Los líderes vean su historial agrupado por fecha
 */
@Entity
@Table(name = "registro_vuelo_diario", indexes = {
        @Index(name = "idx_fecha_registro", columnList = "fecha_registro"),
        @Index(name = "idx_registrado_por", columnList = "registrado_por_id"),
        @Index(name = "idx_vuelo_fecha", columnList = "vuelo_itinerario_id,fecha_registro")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistroVueloDiario extends EstacionScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Referencia al vuelo del itinerario (cargado por el administrador).
     * NO se duplica información del vuelo, solo se referencia.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vuelo_itinerario_id", nullable = false)
    private Vuelo vueloItinerario;

    /**
     * Líder que registró este vuelo para el día.
     * Permite trazabilidad de quién activó cada vuelo.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registrado_por_id", nullable = false)
    private Usuario registradoPor;

    /**
     * Fecha del día para el cual se registró este vuelo.
     * Permite agrupar registros por jornada diaria.
     */
    @Column(name = "fecha_registro", nullable = false)
    private LocalDate fechaRegistro;

    /**
     * Timestamp exacto del momento del registro.
     * Para auditoría y orden cronológico de registros del mismo día.
     */
    @Column(name = "registrado_en", nullable = false)
    private LocalDateTime registradoEn;

    /**
     * Estado del registro (soft-delete).
     * true = activo, false = eliminado/cancelado
     */
    @Column(name = "activo", nullable = false, columnDefinition = "TINYINT DEFAULT 1")
    private Boolean active = true;

    /**
     * Recursos habilitados para este registro específico.
     * Cada registro puede tener diferentes recursos aunque sea el mismo vuelo.
     *
     * Ejemplo: El vuelo PU302 puede registrarse en diferentes días con diferentes hoteles.
     */
    @OneToMany(mappedBy = "registroVueloDiario", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<VueloRecurso> recursos = new ArrayList<>();

    /**
     * Observaciones específicas de este registro.
     * Por ejemplo: "Contingencia por mal clima", "Vuelo reprogramado"
     */
    @Column(columnDefinition = "TEXT")
    private String observaciones;

    // ═══════════════════════════════════════════════════════════════════════
    // Métodos de utilidad
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Añade un recurso a este registro y establece la relación bidireccional.
     */
    public void agregarRecurso(VueloRecurso recurso) {
        recursos.add(recurso);
        recurso.setRegistroVueloDiario(this);
    }

    /**
     * Remueve un recurso de este registro.
     */
    public void removerRecurso(VueloRecurso recurso) {
        recursos.remove(recurso);
        recurso.setRegistroVueloDiario(null);
    }

    /**
     * Retorna información del código de vuelo para logging.
     */
    @Transient
    public String getCodigoVuelo() {
        return vueloItinerario != null ? vueloItinerario.getCodigoVuelo() : "N/A";
    }
}

