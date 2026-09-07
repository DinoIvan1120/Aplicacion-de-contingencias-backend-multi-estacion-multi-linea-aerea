package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.dto.request.ActualizarServiciosRequest;
import com.saasa.contingencias.domain.dto.response.ReporteDetalleResponse;
import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.ServicioAsignadoRepository;
import com.saasa.contingencias.domain.repository.ServicioProveedorRepository;
import com.saasa.contingencias.domain.repository.VueloRecursoRepository;
import com.saasa.contingencias.service.IDisponibilidadService;
import com.saasa.contingencias.service.IReporteServicioBuilder;
import com.saasa.contingencias.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Implementación del constructor de servicios para reportes.
 *
 * Extraído de ReporteServiceImpl (métodos buildServicioHotelDetalle,
 * buildServicioTransporteDetalle, buildServicioRestauranteDetalle,
 * crearServicioHotel, crearServicioTransporte, crearServicioRestaurante
 * y obtenerPrecioServicio).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReporteServicioBuilderImpl implements IReporteServicioBuilder {

    private final VueloRecursoRepository vueloRecursoRepository;
    private final ServicioAsignadoRepository servicioAsignadoRepository;
    private final ServicioProveedorRepository servicioProveedorRepository;
    private final IDisponibilidadService disponibilidadService;

    // ── Build detalle para lectura ────────────────────────────────────────────

    @Override
    public ReporteDetalleResponse.ServicioDetalleResponse buildHotelDetalle(ServicioAsignado servicio) {
        VueloRecurso vueloRecurso = servicio.getVueloRecurso();
        Proveedor proveedor = vueloRecurso.getProveedor();

        String tipoHabitacion = servicio.getTipoHabitacion();
        BigDecimal precioHabitacion = obtenerPrecio(proveedor.getId(),
                "HABITACION_" + (tipoHabitacion != null ? tipoHabitacion.toUpperCase() : "SIMPLE"));

        BigDecimal precioDesayuno = servicio.getDesayuno()
                ? obtenerPrecio(proveedor.getId(), "HOTEL_DESAYUNO") : BigDecimal.ZERO;
        BigDecimal precioAlmuerzo = servicio.getAlmuerzo()
                ? obtenerPrecio(proveedor.getId(), "HOTEL_ALMUERZO") : BigDecimal.ZERO;
        BigDecimal precioCena = servicio.getCena()
                ? obtenerPrecio(proveedor.getId(), "HOTEL_CENA") : BigDecimal.ZERO;
        BigDecimal precioSnack = servicio.getSnack()
                ? obtenerPrecio(proveedor.getId(), "HOTEL_SNACK") : BigDecimal.ZERO;

        return new ReporteDetalleResponse.ServicioDetalleResponse(
                servicio.getId(), vueloRecurso.getId(),
                proveedor.getId(), proveedor.getNombre(), proveedor.getTipo().name(),
                tipoHabitacion, servicio.getCantidad(), precioHabitacion,
                servicio.getDesayuno(), servicio.getAlmuerzo(),
                servicio.getCena(), servicio.getSnack(),
                precioDesayuno, precioAlmuerzo, precioCena, precioSnack,
                null, null,
                servicio.getMontoSubtotal(),
                servicio.getFechaIngreso(), servicio.getFechaSalida()
        );
    }

    @Override
    public ReporteDetalleResponse.ServicioDetalleResponse buildTransporteDetalle(ServicioAsignado servicio) {
        VueloRecurso vueloRecurso = servicio.getVueloRecurso();
        Proveedor proveedor = vueloRecurso.getProveedor();

        return new ReporteDetalleResponse.ServicioDetalleResponse(
                servicio.getId(), vueloRecurso.getId(),
                proveedor.getId(), proveedor.getNombre(), proveedor.getTipo().name(),
                null, null, BigDecimal.ZERO,
                false, false, false, false,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                servicio.getTipoTransporte(), servicio.getCantidad(),
                servicio.getMontoSubtotal(),
                null, null
        );
    }

    @Override
    public ReporteDetalleResponse.ServicioDetalleResponse buildRestauranteDetalle(ServicioAsignado servicio) {
        VueloRecurso vueloRecurso = servicio.getVueloRecurso();
        Proveedor proveedor = vueloRecurso.getProveedor();

        BigDecimal precioDesayuno = servicio.getDesayuno()
                ? obtenerPrecio(proveedor.getId(), "RESTAURANTE_DESAYUNO") : BigDecimal.ZERO;
        BigDecimal precioAlmuerzo = servicio.getAlmuerzo()
                ? obtenerPrecio(proveedor.getId(), "RESTAURANTE_ALMUERZO") : BigDecimal.ZERO;
        BigDecimal precioCena = servicio.getCena()
                ? obtenerPrecio(proveedor.getId(), "RESTAURANTE_CENA") : BigDecimal.ZERO;
        BigDecimal precioSnack = servicio.getSnack()
                ? obtenerPrecio(proveedor.getId(), "RESTAURANTE_SNACK") : BigDecimal.ZERO;

        return new ReporteDetalleResponse.ServicioDetalleResponse(
                servicio.getId(), vueloRecurso.getId(),
                proveedor.getId(), proveedor.getNombre(), proveedor.getTipo().name(),
                null, null, BigDecimal.ZERO,
                servicio.getDesayuno(), servicio.getAlmuerzo(),
                servicio.getCena(), servicio.getSnack(),
                precioDesayuno, precioAlmuerzo, precioCena, precioSnack,
                null, servicio.getCantidad(),
                servicio.getMontoSubtotal(),
                null, null
        );
    }

    // ── Crear y persistir servicios (para actualizarServicios) ────────────────

    @Override
    public BigDecimal crearServicioHotel(Atencion atencion, ActualizarServiciosRequest request) {

        // findRecursoParaEditar toma lock pesimista (igual que asignarServicios) para
        // que dos ediciones/asignaciones simultáneas sobre el mismo hotel no se pisen.
        VueloRecurso vueloRecurso = findRecursoParaEditar(request.hotelVueloRecursoId(), "hotel");
        Proveedor proveedor = vueloRecurso.getProveedor();

        String tipoHabitacion = request.tipoHabitacion() != null
                ? request.tipoHabitacion().toUpperCase() : "SIMPLE";
        int cantidadHabitaciones = request.cantidadHabitaciones() != null
                ? request.cantidadHabitaciones() : 1;

        // Los servicios antiguos de esta atención ya se eliminaron en
        // actualizarServicios() ANTES de llegar aquí (mismo @Transactional),
        // así que este conteo no incluye el cupo que la propia atención tenía
        // reservado — no hace falta excluirlo aparte.
        disponibilidadService.validarDisponibilidadHotel(vueloRecurso, tipoHabitacion, cantidadHabitaciones);

        BigDecimal montoPorHabitacion = obtenerPrecio(proveedor.getId(), "HABITACION_" + tipoHabitacion);

        if (Boolean.TRUE.equals(request.hotelDesayuno()))
            montoPorHabitacion = montoPorHabitacion.add(obtenerPrecio(proveedor.getId(), "HOTEL_DESAYUNO"));
        if (Boolean.TRUE.equals(request.hotelAlmuerzo()))
            montoPorHabitacion = montoPorHabitacion.add(obtenerPrecio(proveedor.getId(), "HOTEL_ALMUERZO"));
        if (Boolean.TRUE.equals(request.hotelCena()))
            montoPorHabitacion = montoPorHabitacion.add(obtenerPrecio(proveedor.getId(), "HOTEL_CENA"));
        if (Boolean.TRUE.equals(request.hotelSnack()))
            montoPorHabitacion = montoPorHabitacion.add(obtenerPrecio(proveedor.getId(), "HOTEL_SNACK"));

        BigDecimal montoTotal = montoPorHabitacion.multiply(BigDecimal.valueOf(cantidadHabitaciones));

        servicioAsignadoRepository.save(ServicioAsignado.builder()
                .atencion(atencion).vueloRecurso(vueloRecurso)
                .tipoDetalle(TipoDetalleEnum.HOTEL).tipoHabitacion(tipoHabitacion)
                .cantidad(cantidadHabitaciones)
                .desayuno(Boolean.TRUE.equals(request.hotelDesayuno()))
                .almuerzo(Boolean.TRUE.equals(request.hotelAlmuerzo()))
                .cena(Boolean.TRUE.equals(request.hotelCena()))
                .snack(Boolean.TRUE.equals(request.hotelSnack()))
                .fechaIngreso(request.fechaIngreso()).fechaSalida(request.fechaSalida())
                .montoUnitario(montoTotal).montoSubtotal(montoTotal)
                .asignadoEn(DateTimeUtil.ahoraEnLima())
                .build());

        log.info("[ReporteServicioBuilder] Hotel: {} - S/ {}", proveedor.getNombre(), montoTotal);
        return montoTotal;
    }

    @Override
    public BigDecimal crearServicioTransporte(Atencion atencion, ActualizarServiciosRequest request) {

        VueloRecurso vueloRecurso = findRecursoParaEditar(request.transporteVueloRecursoId(), "transporte");
        Proveedor proveedor = vueloRecurso.getProveedor();

        String tipoTransporte = request.tipoTransporte() != null
                ? request.tipoTransporte().toUpperCase() : "INDIVIDUAL";
        int cantidadPasajeros = request.cantidadPasajeros() != null
                ? request.cantidadPasajeros() : 1;

        // Servicios antiguos de esta atención ya eliminados antes de llegar aquí
        // (ver nota en crearServicioHotel).
        disponibilidadService.validarDisponibilidadGeneral(vueloRecurso, cantidadPasajeros);

        // Caso especial "AMBOS" (Aeropuerto-Domicilio + Domicilio-Aeropuerto):
        // el precio unitario es la suma de los dos precios configurados en el proveedor.
        BigDecimal precioUnitario = "AMBOS".equals(tipoTransporte)
                ? obtenerPrecio(proveedor.getId(), "TRANSPORTE_INDIVIDUAL")
                .add(obtenerPrecio(proveedor.getId(), "TRANSPORTE_GRUPAL"))
                : obtenerPrecio(proveedor.getId(), "TRANSPORTE_" + tipoTransporte);
        // ✅ FIX: el transporte es un precio FIJO por el servicio (el vehículo/ruta),
        // NO se multiplica por la cantidad de pasajeros — igual que en la asignación
        // original de servicios. Antes duplicaba (o más) el costo del transporte al
        // editar servicios desde el reporte con 2+ pasajeros.
        BigDecimal montoTotal = precioUnitario;

        //BigDecimal precioUnitario = obtenerPrecio(proveedor.getId(), "TRANSPORTE_" + tipoTransporte);
        //BigDecimal montoTotal = precioUnitario.multiply(BigDecimal.valueOf(request.cantidadPasajeros()));

        servicioAsignadoRepository.save(ServicioAsignado.builder()
                .atencion(atencion).vueloRecurso(vueloRecurso)
                .tipoDetalle(TipoDetalleEnum.TRANSPORTE).tipoTransporte(tipoTransporte)
                .cantidad(cantidadPasajeros)
                .montoUnitario(precioUnitario).montoSubtotal(montoTotal)
                .asignadoEn(DateTimeUtil.ahoraEnLima())
                .build());

        log.info("[ReporteServicioBuilder] Transporte: {} - S/ {}", proveedor.getNombre(), montoTotal);
        return montoTotal;
    }

    @Override
    public BigDecimal crearServicioRestaurante(Atencion atencion, ActualizarServiciosRequest request) {

        VueloRecurso vueloRecurso = findRecursoParaEditar(request.restauranteVueloRecursoId(), "restaurante");
        Proveedor proveedor = vueloRecurso.getProveedor();

        int cantidadCubiertos = request.cantidadCubiertos() != null ? request.cantidadCubiertos() : 1;

        // Servicios antiguos de esta atención ya eliminados antes de llegar aquí
        // (ver nota en crearServicioHotel).
        disponibilidadService.validarDisponibilidadGeneral(vueloRecurso, cantidadCubiertos);

        BigDecimal montoPorCubierto = BigDecimal.ZERO;
        if (Boolean.TRUE.equals(request.restauranteDesayuno()))
            montoPorCubierto = montoPorCubierto.add(obtenerPrecio(proveedor.getId(), "RESTAURANTE_DESAYUNO"));
        if (Boolean.TRUE.equals(request.restauranteAlmuerzo()))
            montoPorCubierto = montoPorCubierto.add(obtenerPrecio(proveedor.getId(), "RESTAURANTE_ALMUERZO"));
        if (Boolean.TRUE.equals(request.restauranteCena()))
            montoPorCubierto = montoPorCubierto.add(obtenerPrecio(proveedor.getId(), "RESTAURANTE_CENA"));
        if (Boolean.TRUE.equals(request.restauranteSnack()))
            montoPorCubierto = montoPorCubierto.add(obtenerPrecio(proveedor.getId(), "RESTAURANTE_SNACK"));

        BigDecimal montoTotal = montoPorCubierto.multiply(BigDecimal.valueOf(cantidadCubiertos));

        servicioAsignadoRepository.save(ServicioAsignado.builder()
                .atencion(atencion).vueloRecurso(vueloRecurso)
                .tipoDetalle(TipoDetalleEnum.RESTAURANTE)
                .cantidad(cantidadCubiertos)
                .desayuno(Boolean.TRUE.equals(request.restauranteDesayuno()))
                .almuerzo(Boolean.TRUE.equals(request.restauranteAlmuerzo()))
                .cena(Boolean.TRUE.equals(request.restauranteCena()))
                .snack(Boolean.TRUE.equals(request.restauranteSnack()))
                .montoUnitario(montoTotal).montoSubtotal(montoTotal)
                .asignadoEn(DateTimeUtil.ahoraEnLima())
                .build());

        log.info("[ReporteServicioBuilder] Restaurante: {} - S/ {}", proveedor.getNombre(), montoTotal);
        return montoTotal;
    }

    // ── Privados ──────────────────────────────────────────────────────────────

    private BigDecimal obtenerPrecio(Long proveedorId, String tipoServicio) {
        return servicioProveedorRepository
                .findByProveedorIdAndTipoServicio(proveedorId, tipoServicio)
                .filter(s -> s.getEstado() != null && s.getEstado() == 1)
                .map(ServicioProveedor::getMonto)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Igual que buscar por id, pero con bloqueo pesimista (findByIdForUpdate) —
     * mismo mecanismo que asignarServicios en AtencionServiceImpl. Evita que
     * una edición de servicios desde Reportes se sobreponga con una asignación
     * nueva (u otra edición) hecha al mismo tiempo sobre el mismo recurso.
     */
    private VueloRecurso findRecursoParaEditar(Long id, String tipo) {
        return vueloRecursoRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "Recurso de " + tipo + " no encontrado: " + id));
    }
}

