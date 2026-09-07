package com.saasa.contingencias.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "servicios_proveedor")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ServicioProveedor extends EstacionScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proveedor_id", nullable = false)
    private Proveedor proveedor;

    @Column(nullable = false, length = 100)
    private String tipoServicio;

    @Column(length = 255)
    private String descripcion;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monto;

    @Column(nullable = false, columnDefinition = "TINYINT DEFAULT 1")
    private Integer estado = 1;
}
