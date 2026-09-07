package com.saasa.contingencias.domain.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "estacion")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Estacion extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "codigo_iata", nullable = false, unique = true, length = 3)
    private String codigoIata;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Column(name = "zona_horaria", nullable = false, length = 50)
    @Builder.Default
    private String zonaHoraria = "America/Lima";

    @Column(nullable = false, columnDefinition = "TINYINT DEFAULT 1")
    @Builder.Default
    private Integer estado = 1;

    /**
     * Key en S3 de la foto del aeropuerto (ej: estaciones-fotos/LIM.jpg).
     * Null mientras no se haya subido una foto — la tarjeta de selección
     * de estación cae al ícono genérico en ese caso.
     */
    @Column(name = "foto_key", length = 255)
    private String fotoKey;
}
