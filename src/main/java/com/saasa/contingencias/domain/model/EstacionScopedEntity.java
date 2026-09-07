package com.saasa.contingencias.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@MappedSuperclass
public abstract class EstacionScopedEntity extends AuditableEntity {

    @Column(name = "estacion_id")
    private Long estacionId;

    @Column(name = "linea_aerea_id")
    private Long lineaAereaId;
}
