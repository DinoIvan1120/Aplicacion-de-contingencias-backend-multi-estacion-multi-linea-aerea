package com.saasa.contingencias.domain.mapping;
import com.saasa.contingencias.domain.dto.response.UsuarioResponse;
import com.saasa.contingencias.domain.model.Usuario;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class UsuarioMapper {

    public UsuarioResponse toResponse(Usuario u){
        if(u == null){
            return null;
        }
        return new UsuarioResponse(
                u.getId(), u.getNombre(), u.getApellido(),
                u.getCorreo(),
                u.getDocumento(),
                u.getCodigoEmpleado(),u.getRol().name(),
                u.getEstado(),u.getCreatedAt()
        );
    }

    public List<UsuarioResponse> toResponseList(List<Usuario>entities){
        if(entities == null){
            return List.of();
        }
        return entities.stream().map(this::toResponse).collect(Collectors.toList());
    }

}
