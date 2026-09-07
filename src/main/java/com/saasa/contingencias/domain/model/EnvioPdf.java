package com.saasa.contingencias.domain.model;

import com.saasa.contingencias.domain.enumeration.EstadoEnvioEnum;
import com.saasa.contingencias.domain.enumeration.TipoEnvioEnum;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "envios_pdf")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EnvioPdf extends EstacionScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "atencion_id", nullable = false)
    private Atencion atencion;

    @Column(nullable = false, length = 150)
    private String correoDestino;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private TipoEnvioEnum tipoEnvio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private EstadoEnvioEnum estadoEnvio;

    @Column(nullable = false, columnDefinition = "TINYINT DEFAULT 0")
    private Integer intentos = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enviado_por")
    private Usuario enviadoPor;

    private LocalDateTime enviadoEn;
}
