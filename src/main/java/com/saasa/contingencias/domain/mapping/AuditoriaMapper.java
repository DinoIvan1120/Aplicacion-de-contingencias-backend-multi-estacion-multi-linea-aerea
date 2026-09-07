package com.saasa.contingencias.domain.mapping;
import com.saasa.contingencias.domain.dto.response.AuditoriaResponse;
import com.saasa.contingencias.domain.model.Auditoria;
import com.saasa.contingencias.domain.model.Usuario;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class AuditoriaMapper {

    public AuditoriaResponse toResponse(Auditoria auditoria){
        if(auditoria == null){
            return null;
        }
        String usuarioNombre = null;
        String usuarioRol = null;

        Usuario usuario = auditoria.getUsuario();
        if(usuario != null){
            usuarioNombre = usuario.getNombre() + " " + usuario.getApellido();
            usuarioRol = usuario.getRol() != null ? usuario.getRol().name(): null;
        }

        return new AuditoriaResponse(
                auditoria.getId(),
                usuario != null ? usuario.getId() : null,
                usuarioNombre,
                usuarioRol,
                auditoria.getAccion(),
                auditoria.getModulo(),
                auditoria.getIpOrigen(),
                auditoria.getDetalle(),
                auditoria.getEntidadTipo(),
                auditoria.getEntidadId(),
                auditoria.getEntidadNombre(),
                auditoria.getCreadoEn()
        );
    }

    public List<AuditoriaResponse> toResponseList(List<Auditoria>entities){
        if(entities == null){
            return List.of();
        }
        return entities.stream().map(this::toResponse).collect(Collectors.toList());
    }
}
