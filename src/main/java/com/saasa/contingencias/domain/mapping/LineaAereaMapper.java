package com.saasa.contingencias.domain.mapping;

import com.saasa.contingencias.domain.dto.response.LineaAereaResponse;
import com.saasa.contingencias.domain.model.LineaAerea;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class LineaAereaMapper {

    public LineaAereaResponse toResponse(LineaAerea l) {
        if (l == null) return null;
        return new LineaAereaResponse(l.getId(), l.getCodigoIata(), l.getNombre(), l.getEstado(), l.getLogoKey(), l.getCreatedAt());
    }

    public List<LineaAereaResponse> toResponseList(List<LineaAerea> entities) {
        if (entities == null) return List.of();
        return entities.stream().map(this::toResponse).collect(Collectors.toList());
    }
}
