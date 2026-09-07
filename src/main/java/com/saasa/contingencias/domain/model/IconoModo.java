package com.saasa.contingencias.domain.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Ícono configurable de una de las dos opciones fijas de la pantalla
 * "Seleccionar Modo" del Administrador Global (Gestionar Estaciones y
 * Aerolíneas / Operar en una estación).
 *
 * No hay una entidad de negocio detrás de estas dos opciones (no son
 * estaciones ni líneas aéreas), así que en vez de forzarlas dentro de
 * alguna otra tabla se creó esta tabla mínima con una sola fila por
 * opción, identificada por {@code clave} — mismo patrón de foto/logo que
 * ya usan {@link Estacion} y LineaAerea (columna *Key con la ubicación en
 * S3, resuelta a URL firmada al leer).
 */
@Entity
@Table(name = "icono_modo")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class IconoModo extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Identifica la opción: "GESTIONAR" (Gestionar Estaciones y Aerolíneas)
     * u "OPERAR" (Operar en una estación). Ver {@code ClaveIconoModoEnum}.
     */
    @Column(nullable = false, unique = true, length = 30)
    private String clave;

    /**
     * Key en S3 del ícono subido (ej: iconos-modo/GESTIONAR.png). Null
     * mientras no se haya subido uno — la pantalla cae al ícono genérico
     * (Building2 / MapPinned) en ese caso.
     */
    @Column(name = "icono_key", length = 255)
    private String iconoKey;
}
