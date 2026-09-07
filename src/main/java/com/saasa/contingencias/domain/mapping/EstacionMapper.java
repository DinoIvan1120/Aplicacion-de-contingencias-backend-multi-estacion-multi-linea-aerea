package com.saasa.contingencias.domain.mapping;

import com.saasa.contingencias.domain.dto.response.EstacionLineaAereaResponse;
import com.saasa.contingencias.domain.dto.response.EstacionResponse;
import com.saasa.contingencias.domain.model.Estacion;
import com.saasa.contingencias.domain.model.EstacionLineaAerea;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class EstacionMapper {

    public EstacionResponse toResponse(Estacion e) {
        if (e == null) return null;
        return new EstacionResponse(
                e.getId(), e.getCodigoIata(), e.getNombre(), e.getZonaHoraria(), e.getEstado(),e.getFotoKey(), e.getCreatedAt()
        );
    }

    public List<EstacionResponse> toResponseList(List<Estacion> entities) {
        if (entities == null) return List.of();
        return entities.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public EstacionLineaAereaResponse toRelacionResponse(EstacionLineaAerea rel) {
        return toRelacionResponse(rel, 0L);
    }

    public EstacionLineaAereaResponse toRelacionResponse(EstacionLineaAerea rel, long totalVuelos) {
        if (rel == null) return null;
        return new EstacionLineaAereaResponse(
                rel.getId(),
                rel.getLineaAerea().getId(),
                rel.getLineaAerea().getCodigoIata(),
                rel.getLineaAerea().getNombre(),
                rel.getEstado(),
                totalVuelos
        );
    }

    public List<EstacionLineaAereaResponse> toRelacionResponseList(List<EstacionLineaAerea> entities) {
        if (entities == null) return List.of();
        return entities.stream().map(this::toRelacionResponse).collect(Collectors.toList());
    }
}
