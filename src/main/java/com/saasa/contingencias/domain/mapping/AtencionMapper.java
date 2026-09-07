package com.saasa.contingencias.domain.mapping;
import com.saasa.contingencias.domain.dto.response.AtencionResponse;
import com.saasa.contingencias.domain.dto.response.ServicioAsignadoResponse;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.model.ServicioAsignado;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class AtencionMapper {

    public AtencionResponse toResponse(Atencion a){
        if(a == null){
            return null;
        }
        return new AtencionResponse(
                a.getId(),
                a.getNumeroCorrelativo(),
                a.getVuelo().getId(),
                a.getVuelo().getCodigoVuelo(),
                a.getNombre(),
                a.getApellido(),
                a.getPnr(),
                a.getCorreo(),
                a.getMontoTotal(),
                a.getCodigoAutorizacion(),
                a.getPdfUrl(),
                a.getEstado().name(),
                a.getAtendidoPor().getNombre() + " " + a.getAtendidoPor().getApellido(),
                a.getCreatedAt(),
                a.getFirmaPasajero(),
                a.getFirmaConforme(),
                a.getFirmaFecha(),
                a.getOrigenFirma() != null ? a.getOrigenFirma().name() : null,
                a.getOrigenFirmaRol()
        );
    }

    public ServicioAsignadoResponse toServicioResponse
            (ServicioAsignado sa){
        if(sa == null){
            return null;
        }
        return new ServicioAsignadoResponse(
                sa.getId(),
                sa.getAtencion().getId(),
                sa.getVueloRecurso() != null ? sa.getVueloRecurso().getId(): null,
                sa.getTipoDetalle().name(),
                sa.getCantidad(),
                sa.getMontoUnitario(),
                sa.getMontoSubtotal(),
                sa.getAsignadoEn(),
                sa.getFechaIngreso(),
                sa.getFechaSalida()
        );
    }

    public List<AtencionResponse>toResponseList(List<Atencion> entities){
        if(entities == null){
            return List.of();
        }
        return entities.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<ServicioAsignadoResponse> toServicioResponseList(List<ServicioAsignado>entities){
        if(entities == null){
            return List.of();
        }
        return entities.stream().map(this::toServicioResponse).collect(Collectors.toList());
    }

}
