package com.saasa.contingencias.domain.mapping;

import com.saasa.contingencias.domain.dto.response.RegistroVueloDiarioResponse;
import com.saasa.contingencias.domain.dto.response.VueloRecursoResponse;
import com.saasa.contingencias.domain.dto.response.VueloResponse;
import com.saasa.contingencias.domain.model.RegistroVueloDiario;
import com.saasa.contingencias.domain.model.Vuelo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class RegistroVueloDiarioMapper {

    /**
     * Convierte entidad a Response DTO completo.
     * Incluye todos los recursos activos y contadores.
     */
    public RegistroVueloDiarioResponse toResponse(RegistroVueloDiario entity) {
        if (entity == null) {
            return null;
        }

        // Mapear vuelo del itinerario (MAPEO MANUAL porque VueloResponse es un record)
        Vuelo vuelo = entity.getVueloItinerario();
        VueloResponse vueloResponse = new VueloResponse(
                vuelo.getId(),
                vuelo.getAerolinea(),
                vuelo.getCodigoVuelo(),
                vuelo.getOrigen(),
                vuelo.getDestino(),
                vuelo.getFechaVuelo(),
                vuelo.getTipoContingencia() != null ? vuelo.getTipoContingencia().name() : null,
                vuelo.getObservaciones(),
                vuelo.getEstado() != null ? vuelo.getEstado().name() : null,
                vuelo.getCreadoPor() != null ? vuelo.getCreadoPor().getId() : null,
                vuelo.getCreadoPor() != null ? vuelo.getCreadoPor().getNombre() : null,
                vuelo.getCreatedAt()
        );

        // Mapear solo recursos ACTIVOS (MAPEO MANUAL porque VueloRecursoResponse es un record)
        List<VueloRecursoResponse> recursosResponse = entity.getRecursos().stream()
                .filter(r -> r.getEstado() != null && r.getEstado() == 1) // Solo activos
                .map(r -> {
                    int simples = r.getHabitacionesSimples() != null ? r.getHabitacionesSimples() : 0;
                    int dobles = r.getHabitacionesDobles() != null ? r.getHabitacionesDobles() : 0;
                    int matrimoniales = r.getHabitacionesMatrimoniales() != null ? r.getHabitacionesMatrimoniales() : 0;
                    int totalHabitaciones = simples + dobles + matrimoniales;

                    return new VueloRecursoResponse(
                            r.getId(),
                            r.getVuelo() != null ? r.getVuelo().getId() : null,
                            r.getProveedor() != null ? r.getProveedor().getId() : null,
                            r.getProveedor() != null ? r.getProveedor().getNombre() : null,
                            r.getProveedor() != null && r.getProveedor().getTipo() != null ? r.getProveedor().getTipo().name() : null,
                            r.getProveedor() != null ? r.getProveedor().getCorreo() : null,
                            r.getHabitacionesSimples(),
                            r.getHabitacionesDobles(),
                            r.getHabitacionesMatrimoniales(),
                            totalHabitaciones > 0 ? totalHabitaciones : r.getCapacidadTotal(),
                            r.getCapacidadTotal(),
                            r.getHabilitadoPor() != null ? r.getHabilitadoPor().getNombre() : null,
                            r.getHabilitadoEn(),
                            r.getEstado()
                    );
                })
                .collect(Collectors.toList());

        // Datos del líder que registró
        Long registradoPorId = entity.getRegistradoPor() != null ? entity.getRegistradoPor().getId() : null;
        String registradoPorNombre = entity.getRegistradoPor() != null ? entity.getRegistradoPor().getNombre() : null;
        String registradoPorCorreo = entity.getRegistradoPor() != null ? entity.getRegistradoPor().getCorreo() : null;

        // Constructor que calcula automáticamente los contadores
        return new RegistroVueloDiarioResponse(
                entity.getId(),
                entity.getFechaRegistro(),
                entity.getRegistradoEn(),
                entity.getObservaciones(),
                entity.getActive(),
                vueloResponse,
                registradoPorId,
                registradoPorNombre,
                registradoPorCorreo,
                recursosResponse
        );
    }

    /**
     * Convierte lista de entidades a lista de DTOs.
     */
    public List<RegistroVueloDiarioResponse> toResponseList(List<RegistroVueloDiario> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }
}
