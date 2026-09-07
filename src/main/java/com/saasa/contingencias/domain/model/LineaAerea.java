package com.saasa.contingencias.domain.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "linea_aerea")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LineaAerea extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "codigo_iata", nullable = false, unique = true, length = 3)
    private String codigoIata;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Column(nullable = false, columnDefinition = "TINYINT DEFAULT 1")
    @Builder.Default
    private Integer estado = 1;

    /**
     * Key en S3 del logo de la aerolínea (ej: contingencias/logos/PUL.png).
     * Null mientras no se haya subido un logo — el PDF cae al header de
     * solo texto en ese caso.
     */
    @Column(name = "logo_key", length = 255)
    private String logoKey;
}
