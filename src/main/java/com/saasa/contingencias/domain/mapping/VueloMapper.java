package com.saasa.contingencias.domain.mapping;
import com.saasa.contingencias.domain.dto.response.RegistroVueloResponse;
import com.saasa.contingencias.domain.dto.response.VueloRecursoResponse;
import com.saasa.contingencias.domain.dto.response.VueloResponse;
import com.saasa.contingencias.domain.model.Vuelo;
import com.saasa.contingencias.domain.model.VueloRecurso;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class VueloMapper {

    public VueloResponse toResponse(Vuelo v){
        if(v == null){
            return null;
        }
        String nombreCreador = v.getCreadoPor() != null ?
                v.getCreadoPor().getNombre() + " " + v.getCreadoPor().getApellido(): null;
        return new VueloResponse(
                v.getId(),v.getAerolinea(), v.getCodigoVuelo(),
                v.getOrigen(),v.getDestino(),v.getFechaVuelo(),
                v.getTipoContingencia().name(),v.getObservaciones(),v.getEstado().name(),
                v.getCreadoPor() !=null ? v.getCreadoPor().getId(): null,
                nombreCreador, v.getCreatedAt()
        );
    }

    public VueloRecursoResponse toRecursoResponse(VueloRecurso vr){
        if(vr == null){
            return null;
        }
        return new VueloRecursoResponse(
                vr.getId(),
                vr.getVuelo().getId(),
                vr.getProveedor().getId(),
                vr.getProveedor().getNombre(),
                vr.getProveedor().getTipo().name(),
                vr.getProveedor().getCorreo(),
                vr.getHabitacionesSimples(),
                vr.getHabitacionesDobles(),
                vr.getHabitacionesMatrimoniales(),
                vr.getTotalHabitaciones(),
                vr.getCapacidadTotal(),
                vr.getHabilitadoPor().getNombre() + " " + vr.getHabilitadoPor().getApellido(),
                vr.getHabilitadoEn(),
                vr.getEstado()
        );
    }

    public RegistroVueloResponse toRegistroResponse(Vuelo v, List<VueloRecurso>recursos){
        if(v == null){
            return null;
        }
        List<VueloRecursoResponse> recursosResp = recursos == null ? List.of() : recursos.stream().map(
                this::toRecursoResponse
        ).toList();

        int hoteles = (int) recursosResp.
                stream().filter(r -> "HOTEL".equals(r.proveedorTipo())).count();
        int transportes = (int) recursosResp.
                stream().filter(r -> "TRANSPORTE".equals(r.proveedorTipo())).count();
        int restaurantes = (int) recursosResp.
                stream().filter(r -> "RESTAURANTE".equals(r.proveedorTipo())).count();

        String creador = v.getCreadoPor() != null
                ? v.getCreadoPor().getNombre() + " " + v.getCreadoPor().getApellido() : null;

        return new RegistroVueloResponse(
                v.getId(), v.getAerolinea(), v.getCodigoVuelo(),
                v.getOrigen(), v.getDestino(), v.getFechaVuelo(),
                null,   // horaVuelo: solo es un campo informativo del frontend, no se persiste en BD
                v.getTipoContingencia().name(), v.getObservaciones(), v.getEstado().name(),
                v.getCreadoPor() != null ? v.getCreadoPor().getId() : null,
                creador, v.getCreatedAt(), v.getUpdatedAt(),
                recursosResp, hoteles, transportes, restaurantes);
    }

    public List<VueloResponse>toResponseList(List<Vuelo>entities){
        if(entities == null){
            return List.of();
        }
        return entities.stream().map(this::toResponse).collect(Collectors.toList());
    }
}
