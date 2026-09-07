package com.saasa.contingencias.domain.model;

import jakarta.persistence.*;

/**
 * Entidad auxiliar para generación atómica de correlativos.
 *
 * Responsabilidad única (SRP): esta clase SOLO existe para
 * que MySQL genere un id AUTO_INCREMENT por INSERT.
 * No tiene lógica de negocio — el formateado "SGC-NNNNNNNNN"
 * ocurre en CorrelativoService, no aquí.
 *
 * Por qué una tabla aparte y no usar MAX(numero_correlativo):
 *  - AUTO_INCREMENT de MySQL es atómico entre múltiples JVMs.
 *  - Los ids nunca se reutilizan aunque se anulen atenciones.
 *  - synchronized solo protege dentro de una JVM; con varias
 *    instancias desplegadas produce colisiones.
 */
@Entity
@Table(
        name = "correlativo_sequence",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_correlativo_estacion_linea",
                columnNames = {"estacion_id", "linea_aerea_id"}
        )
)
public class CorrelativoSequence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "estacion_id", nullable = false)
    private Long estacionId;

    @Column(name = "linea_aerea_id", nullable = false)
    private Long lineaAereaId;

    @Column(name = "ultimo_numero", nullable = false)
    private Long ultimoNumero;

    public Long getId() { return id; }
    public Long getEstacionId() { return estacionId; }
    public Long getLineaAereaId() { return lineaAereaId; }
    public Long getUltimoNumero() { return ultimoNumero; }
}
