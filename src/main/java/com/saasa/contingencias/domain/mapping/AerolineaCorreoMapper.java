package com.saasa.contingencias.domain.mapping;

import com.saasa.contingencias.domain.dto.response.AerolineaCorreoResponse;
import com.saasa.contingencias.domain.model.AerolineaCorreo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class AerolineaCorreoMapper {

    public AerolineaCorreoResponse toResponse(AerolineaCorreo a) {
        if (a == null) return null;
        return new AerolineaCorreoResponse(
                a.getId(),
                a.getEstacionId(),
                a.getLineaAereaId(),
                a.getAerolinea(),
                a.getCorreo(),
                a.getObservaciones(),
                a.getEstado(),
                a.getCreatedAt()
        );
    }

    public List<AerolineaCorreoResponse> toResponseList(List<AerolineaCorreo> entities) {
        if (entities == null) return List.of();
        return entities.stream().map(this::toResponse).collect(Collectors.toList());
    }
}
