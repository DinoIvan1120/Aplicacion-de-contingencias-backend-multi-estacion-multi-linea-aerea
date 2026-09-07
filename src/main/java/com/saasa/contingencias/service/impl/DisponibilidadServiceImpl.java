package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.dto.response.DisponibilidadResponse;
import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import com.saasa.contingencias.domain.model.RegistroVueloDiario;
import com.saasa.contingencias.domain.model.ServicioAsignado;
import com.saasa.contingencias.domain.model.VueloRecurso;
import com.saasa.contingencias.domain.repository.RegistroVueloDiarioRepository;
import com.saasa.contingencias.domain.repository.ServicioAsignadoRepository;
import com.saasa.contingencias.service.IDisponibilidadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.saasa.contingencias.config.exception.BadRequestException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisponibilidadServiceImpl implements IDisponibilidadService {

    private final RegistroVueloDiarioRepository registroRepository;
    private final ServicioAsignadoRepository servicioAsignadoRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    @Transactional(readOnly = true)
    public DisponibilidadResponse obtenerDisponibilidad(Long registroVueloDiarioId) {
        log.info("Obteniendo disponibilidad para registro diario ID: {}", registroVueloDiarioId);

        RegistroVueloDiario registro = registroRepository.findById(registroVueloDiarioId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "Registro diario no encontrado: " + registroVueloDiarioId));

        // Obtener todos los recursos habilitados para este registro
        List<VueloRecurso> recursos = registro.getRecursos().stream()
                .filter(r -> r.getEstado() != null && r.getEstado() == 1)
                .toList();

        // Obtener todos los servicios asignados relacionados con estos recursos
        List<Long> recursoIds = recursos.stream()
                .map(VueloRecurso::getId)
                .toList();

        Map<Long, List<ServicioAsignado>> serviciosPorRecurso = servicioAsignadoRepository
                .findByVueloRecursoIdIn(recursoIds).stream()
                .collect(Collectors.groupingBy(s -> s.getVueloRecurso().getId()));

        // Separar por tipo de proveedor
        List<DisponibilidadResponse.RecursoDisponibleResponse> hoteles = new ArrayList<>();
        List<DisponibilidadResponse.RecursoDisponibleResponse> transportes = new ArrayList<>();
        List<DisponibilidadResponse.RecursoDisponibleResponse> restaurantes = new ArrayList<>();

        for (VueloRecurso recurso : recursos) {
            List<ServicioAsignado> serviciosAsignados = serviciosPorRecurso.getOrDefault(
                    recurso.getId(), List.of());

            DisponibilidadResponse.RecursoDisponibleResponse disponible = calcularDisponibilidad(recurso, serviciosAsignados);

            TipoProveedorEnum tipo = recurso.getProveedor().getTipo();
            if (tipo == TipoProveedorEnum.HOTEL) {
                hoteles.add(disponible);
            } else if (tipo == TipoProveedorEnum.TRANSPORTE) {
                transportes.add(disponible);
            } else if (tipo == TipoProveedorEnum.RESTAURANTE) {
                restaurantes.add(disponible);
            }
        }

        log.info("Disponibilidad calculada - Hoteles: {}, Transportes: {}, Restaurantes: {}",
                hoteles.size(), transportes.size(), restaurantes.size());

        return new DisponibilidadResponse(hoteles, transportes, restaurantes);
    }

    @Override
    public void notificarCambioDisponibilidad(Long registroVueloDiarioId) {
        log.info("Notificando cambio de disponibilidad para registro: {}", registroVueloDiarioId);

        DisponibilidadResponse disponibilidad = obtenerDisponibilidad(registroVueloDiarioId);

        // Enviar notificación vía WebSocket
        messagingTemplate.convertAndSend(
                "/topic/disponibilidad/" + registroVueloDiarioId,
                disponibilidad
        );

        log.info("Notificación WebSocket enviada a /topic/disponibilidad/{}", registroVueloDiarioId);
    }

    // ── Validaciones antes de asignar — llamadas desde AtencionServiceImpl ────

    @Override
    public void validarDisponibilidadHotel(VueloRecurso recurso,
                                           String tipoHabitacion,
                                           int cantidadRequerida) {
        List<ServicioAsignado> serviciosAsignados = servicioAsignadoRepository
                .findByVueloRecursoIdIn(List.of(recurso.getId()));

        int habitacionesUsadas = serviciosAsignados.stream()
                .filter(s -> tipoHabitacion.equals(s.getTipoHabitacion()))
                .mapToInt(s -> s.getCantidad() != null ? s.getCantidad() : 1)
                .sum();

        int habitacionesTotales = switch (tipoHabitacion) {
            case "SIMPLE"       -> recurso.getHabitacionesSimples()       != null ? recurso.getHabitacionesSimples()       : 0;
            case "DOBLE"        -> recurso.getHabitacionesDobles()        != null ? recurso.getHabitacionesDobles()        : 0;
            case "MATRIMONIAL"  -> recurso.getHabitacionesMatrimoniales() != null ? recurso.getHabitacionesMatrimoniales() : 0;
            default             -> 0;
        };

        int disponibles = habitacionesTotales - habitacionesUsadas;

        if (disponibles < cantidadRequerida) {
            throw new BadRequestException(String.format(
                    "No hay suficientes habitaciones %s disponibles. Disponibles: %d, Requeridas: %d",
                    tipoHabitacion, disponibles, cantidadRequerida));
        }
    }

    @Override
    public void validarDisponibilidadGeneral(VueloRecurso recurso, int cantidadRequerida) {
        List<ServicioAsignado> serviciosAsignados = servicioAsignadoRepository
                .findByVueloRecursoIdIn(List.of(recurso.getId()));

        int capacidadTotal = recurso.getCapacidadTotal() != null ? recurso.getCapacidadTotal() : 0;
        int capacidadUsada = serviciosAsignados.stream()
                .mapToInt(s -> s.getCantidad() != null ? s.getCantidad() : 1)
                .sum();
        int disponible = capacidadTotal - capacidadUsada;

        if (disponible < cantidadRequerida) {
            throw new BadRequestException(String.format(
                    "No hay suficiente capacidad disponible en %s. Disponible: %d, Requerido: %d",
                    recurso.getProveedor().getNombre(), disponible, cantidadRequerida));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Métodos privados de cálculo
    // ═══════════════════════════════════════════════════════════════════════

    private DisponibilidadResponse.RecursoDisponibleResponse calcularDisponibilidad(
            VueloRecurso recurso,
            List<ServicioAsignado> serviciosAsignados) {

        TipoProveedorEnum tipo = recurso.getProveedor().getTipo();

        if (tipo == TipoProveedorEnum.HOTEL) {
            return calcularDisponibilidadHotel(recurso, serviciosAsignados);
        } else {
            return calcularDisponibilidadGeneral(recurso, serviciosAsignados);
        }
    }

    private DisponibilidadResponse.RecursoDisponibleResponse calcularDisponibilidadHotel(
            VueloRecurso recurso,
            List<ServicioAsignado> serviciosAsignados) {

        // Calcular habitaciones usadas por tipo
        int simplesUsadas = (int) serviciosAsignados.stream()
                .filter(s -> "SIMPLE".equals(s.getTipoHabitacion()))
                .mapToInt(s -> s.getCantidad() != null ? s.getCantidad() : 1)
                .sum();

        int doblesUsadas = (int) serviciosAsignados.stream()
                .filter(s -> "DOBLE".equals(s.getTipoHabitacion()))
                .mapToInt(s -> s.getCantidad() != null ? s.getCantidad() : 1)
                .sum();

        int matrimonialesUsadas = (int) serviciosAsignados.stream()
                .filter(s -> "MATRIMONIAL".equals(s.getTipoHabitacion()))
                .mapToInt(s -> s.getCantidad() != null ? s.getCantidad() : 1)
                .sum();

        // Disponibilidad total
        int simples = recurso.getHabitacionesSimples() != null ? recurso.getHabitacionesSimples() : 0;
        int dobles = recurso.getHabitacionesDobles() != null ? recurso.getHabitacionesDobles() : 0;
        int matrimoniales = recurso.getHabitacionesMatrimoniales() != null ? recurso.getHabitacionesMatrimoniales() : 0;

        int simplesDisponibles = Math.max(0, simples - simplesUsadas);
        int doblesDisponibles = Math.max(0, dobles - doblesUsadas);
        int matrimonialesDisponibles = Math.max(0, matrimoniales - matrimonialesUsadas);

        int totalHabitaciones = simples + dobles + matrimoniales;
        int totalUsadas = simplesUsadas + doblesUsadas + matrimonialesUsadas;
        int totalDisponibles = simplesDisponibles + doblesDisponibles + matrimonialesDisponibles;

        boolean agotado = totalDisponibles == 0;

        return new DisponibilidadResponse.RecursoDisponibleResponse(
                recurso.getId(),
                recurso.getProveedor().getId(),
                recurso.getProveedor().getNombre(),
                recurso.getProveedor().getTipo().name(),
                recurso.getProveedor().getCorreo(),
                simples, dobles, matrimoniales,
                simplesUsadas, doblesUsadas, matrimonialesUsadas,
                simplesDisponibles, doblesDisponibles, matrimonialesDisponibles,
                totalHabitaciones, totalUsadas, totalDisponibles,
                null, null, null,
                agotado
        );
    }

    private DisponibilidadResponse.RecursoDisponibleResponse calcularDisponibilidadGeneral(
            VueloRecurso recurso,
            List<ServicioAsignado> serviciosAsignados) {

        int capacidadTotal = recurso.getCapacidadTotal() != null ? recurso.getCapacidadTotal() : 0;
        // Un pasajero puede ocupar más de 1 pax/cubierto si el agente puso cantidad > 1.
        int capacidadUsada = serviciosAsignados.stream()
                .mapToInt(s -> s.getCantidad() != null ? s.getCantidad() : 1)
                .sum();
        int capacidadDisponible = Math.max(0, capacidadTotal - capacidadUsada);

        boolean agotado = capacidadDisponible == 0;

        return new DisponibilidadResponse.RecursoDisponibleResponse(
                recurso.getId(),
                recurso.getProveedor().getId(),
                recurso.getProveedor().getNombre(),
                recurso.getProveedor().getTipo().name(),
                recurso.getProveedor().getCorreo(),
                null, null, null,
                null, null, null,
                null, null, null,
                null, null, null,
                capacidadTotal, capacidadUsada, capacidadDisponible,
                agotado
        );
    }
}
