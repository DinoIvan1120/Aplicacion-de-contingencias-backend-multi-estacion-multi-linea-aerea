package com.saasa.contingencias.domain.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "estacion_linea_aerea",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_estacion_linea",
                columnNames = {"estacion_id", "linea_aerea_id"}
        )
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EstacionLineaAerea extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "estacion_id", nullable = false)
    private Estacion estacion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linea_aerea_id", nullable = false)
    private LineaAerea lineaAerea;

    @Column(nullable = false, columnDefinition = "TINYINT DEFAULT 1")
    @Builder.Default
    private Integer estado = 1;
}
