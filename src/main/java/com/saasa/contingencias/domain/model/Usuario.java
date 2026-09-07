package com.saasa.contingencias.domain.model;

import com.saasa.contingencias.domain.enumeration.RolEnum;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "usuarios")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Usuario extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Column(nullable = false, length = 100)
    private String apellido;

    @Column(unique = true, length = 150)
    private String correo;

    @Column(nullable = false, length = 20)
    private String documento;

    @Column(nullable = false, unique = true, length = 150)
    private String codigoEmpleado;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RolEnum rol;

    @Column(nullable = false, columnDefinition = "TINYINT DEFAULT 1")
    private Integer estado = 1;
}
