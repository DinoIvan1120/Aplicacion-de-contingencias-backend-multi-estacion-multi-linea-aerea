package com.saasa.contingencias.domain.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Parametrización de correos de notificación de aerolíneas.
 * El administrador registra aquí el correo al que se debe notificar
 * (enviar copia del voucher) cuando se atiende a un pasajero de una
 * aerolínea determinada.
 *
 * ACTUALIZADO — Multi-estación: cada correo pertenece a un par
 * estación+línea aérea (heredado de EstacionScopedEntity), igual que
 * Proveedor, Vuelo, Atencion, etc. Ya no es un registro global por
 * nombre de aerolínea: la MISMA aerolínea (p. ej. LATAM) puede tener un
 * correo distinto en cada estación donde opera. El nombre en `aerolinea`
 * se resuelve SIEMPRE desde el catálogo (LineaAerea.nombre) al crear —
 * ya no es texto libre editable por el usuario, para evitar duplicados
 * por typo ("LATAM" vs "Latam").
 */
@Entity
@Table(name = "aerolineas_correo",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_aerolineas_correo_estacion_linea",
                columnNames = {"estacion_id", "linea_aerea_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AerolineaCorreo extends EstacionScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String aerolinea;

    @Column(nullable = false, length = 150)
    private String correo;

    @Column(length = 255)
    private String observaciones;

    @Column(nullable = false, columnDefinition = "TINYINT DEFAULT 1")
    private Integer estado = 1;
}

