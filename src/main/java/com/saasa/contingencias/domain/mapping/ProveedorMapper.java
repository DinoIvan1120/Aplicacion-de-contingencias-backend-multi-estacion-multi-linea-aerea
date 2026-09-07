package com.saasa.contingencias.domain.mapping;
import com.saasa.contingencias.domain.dto.response.ProveedorConServiciosResponse;
import com.saasa.contingencias.domain.dto.response.ProveedorResponse;
import com.saasa.contingencias.domain.dto.response.ServicioProveedorResponse;
import com.saasa.contingencias.domain.model.Proveedor;
import com.saasa.contingencias.domain.model.ServicioProveedor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ProveedorMapper {

    public ProveedorResponse toResponse(Proveedor p){
        if(p == null){
            return null;
        }
        return new ProveedorResponse(
                p.getId(),
                p.getTipo().name(),
                p.getNombre(),
                p.getRuc(),
                p.getDireccion(),
                p.getTelefono(),
                p.getCorreo(),
                p.getEstado(),
                p.getCreatedAt()
        );
    }

    public ServicioProveedorResponse toServicioResponse(ServicioProveedor sp){
        if(sp == null){
            return null;
        }
        return new ServicioProveedorResponse(
                sp.getId(),
                sp.getProveedor().getId(),
                sp.getTipoServicio(),
                sp.getDescripcion(),
                sp.getMonto(),sp.getEstado()
        );
    }

    public ProveedorConServiciosResponse toConServiciosResponse(Proveedor p,List<ServicioProveedor>servicios){
        if(p == null){
            return null;
        }
        List<ServicioProveedorResponse> serviciosResponse = servicios == null
                ? List.of()
                : servicios.stream().map(this::toServicioResponse).toList();

        return new ProveedorConServiciosResponse(
                p.getId(), p.getTipo().name(), p.getNombre(), p.getRuc(),
                p.getDireccion(), p.getTelefono(), p.getCorreo(),
                p.getEstado(), p.getCreatedAt(),
                serviciosResponse);
    }

    public List<ProveedorResponse> toResponseList(List<Proveedor> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream().map(this::toResponse).collect(Collectors.toList());
    }

}
