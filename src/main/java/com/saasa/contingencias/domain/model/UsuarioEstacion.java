package com.saasa.contingencias.domain.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Tabla puente que resuelve el acceso de un Usuario a Estacion (+
 * opcionalmente Línea Aérea dentro de ella).
 *
 * Un usuario SIN registros aquí es Administrador Global: opera sin
 * restricción, pero debe elegir un contexto de trabajo (estación+línea)
 * activo para operar (ver EstacionContext#resolverContextoActivo).
 *
 * Un usuario con una o varias filas activas aquí queda restringido a
 * esa(s) estación(es). Dentro de cada fila, `lineaAerea == null` significa
 * "todas las líneas de esa estación" (p. ej. Administrador de Estación sin
 * restricción de línea); `lineaAerea` no nula fija al usuario a una única
 * aerolínea dentro de esa estación (p. ej. Líder/Agente SAASA de una sola
 * aerolínea) — es el caso descrito en el pedido de extensión: "usuarios
 * solo de Plus Ultra".
 *
 * AGREGADO en la extensión estación+línea aérea: la columna
 * `linea_aerea_id`, nullable, sobre la tabla puente que ya existía solo
 * por estación — se evita crear una tabla nueva porque el par
 * (estación, línea) ya identifica unívocamente el scope de una fila.
 */
@Entity
@Table(
        name = "usuario_estacion",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_usuario_estacion_linea",
                columnNames = {"usuario_id", "estacion_id", "linea_aerea_id"}
        )
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UsuarioEstacion extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "estacion_id", nullable = false)
    private Estacion estacion;

    /** null = todas las líneas aéreas de la estación. No nula = restringido a esa línea. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linea_aerea_id", nullable = true)
    private LineaAerea lineaAerea;

    /** 1 = activo, 0 = inactivo. */
    @Column(nullable = false, columnDefinition = "TINYINT DEFAULT 1")
    @Builder.Default
    private Integer estado = 1;
}
