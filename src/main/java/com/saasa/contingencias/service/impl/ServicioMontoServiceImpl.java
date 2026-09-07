package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.domain.dto.request.ServicioAsignadoRequest;
import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import com.saasa.contingencias.domain.model.Proveedor;
import com.saasa.contingencias.domain.model.ServicioProveedor;
import com.saasa.contingencias.domain.model.VueloRecurso;
import com.saasa.contingencias.domain.repository.ServicioProveedorRepository;
import com.saasa.contingencias.service.IServicioMontoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
/**
 * Implementación del cálculo de precios de servicios.
 *
 * Extraído de AtencionServiceImpl (métodos obtenerMontoServicio,
 * obtenerMontoRestauranteConPrefijo, buscarPrecioServicio, determinarTipoServicio)
 * y de ReporteServiceImpl (método obtenerPrecioServicio).
 *
 * Al centralizar aquí se elimina la duplicación entre ambos servicios.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServicioMontoServiceImpl implements IServicioMontoService {

    private final ServicioProveedorRepository servicioProveedorRepository;

    @Override
    public BigDecimal calcularMonto(VueloRecurso vueloRecurso, ServicioAsignadoRequest req) {
        Proveedor proveedor = vueloRecurso.getProveedor();

        if (req.tipoDetalle() == TipoDetalleEnum.HOTEL) {
            return calcularMontoHotel(proveedor, req);
        }

        if (req.tipoDetalle() == TipoDetalleEnum.RESTAURANTE) {
            return calcularMontoRestaurante(proveedor, req, "RESTAURANTE_");
        }

        // TRANSPORTE: caso especial "AMBOS" (Aeropuerto-Domicilio + Domicilio-Aeropuerto)
        // Se modela como la suma de los dos precios ya configurados en el proveedor,
        // en lugar de crear un tercer precio independiente. No requiere nuevos campos
        // en ServicioProveedor ni en el formulario del proveedor.
        if (req.tipoDetalle() == TipoDetalleEnum.TRANSPORTE
                && req.tipoTransporte() != null
                && "AMBOS".equalsIgnoreCase(req.tipoTransporte())) {
            BigDecimal precioIndividual = buscarPrecio(proveedor.getId(), "TRANSPORTE_INDIVIDUAL");
            BigDecimal precioGrupal = buscarPrecio(proveedor.getId(), "TRANSPORTE_GRUPAL");
            BigDecimal montoAmbos = precioIndividual.add(precioGrupal);

            if (montoAmbos.compareTo(BigDecimal.ZERO) == 0) {
                log.warn("[ServicioMonto] Sin precio para TRANSPORTE AMBOS en proveedor {}",
                        proveedor.getNombre());
            } else {
                log.info("[ServicioMonto] Precio AMBOS: {} = S/ {} (Aeropuerto-Domicilio {} + Domicilio-Aeropuerto {})",
                        proveedor.getNombre(), montoAmbos, precioIndividual, precioGrupal);
            }
            return montoAmbos;
        }

        // TRANSPORTE u otros
        String tipoServicio = determinarTipoServicio(req);
        BigDecimal monto = buscarPrecio(proveedor.getId(), tipoServicio);

        if (monto.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("[ServicioMonto] Sin precio para {}: {} en proveedor {}",
                    req.tipoDetalle(), tipoServicio, proveedor.getNombre());
        } else {
            log.info("[ServicioMonto] Precio: {} - {} = S/ {}",
                    proveedor.getNombre(), tipoServicio, monto);
        }
        return monto;
    }

    @Override
    public BigDecimal buscarPrecio(Long proveedorId, String tipoServicio) {
        Optional<ServicioProveedor> opt = servicioProveedorRepository
                .findByProveedorIdAndTipoServicio(proveedorId, tipoServicio);

        if (opt.isEmpty()) {
            log.warn("[ServicioMonto] No existe precio para servicio: {}", tipoServicio);
            return BigDecimal.ZERO;
        }

        ServicioProveedor servicio = opt.get();
        if (servicio.getEstado() == null || servicio.getEstado() != 1) {
            log.warn("[ServicioMonto] Servicio {} está inactivo", tipoServicio);
            return BigDecimal.ZERO;
        }

        return servicio.getMonto();
    }

    // ── Privados ──────────────────────────────────────────────────────────────

    private BigDecimal calcularMontoHotel(Proveedor proveedor, ServicioAsignadoRequest req) {
        BigDecimal total = BigDecimal.ZERO;

        // 1. Precio de la habitación
        String tipoHabitacion = determinarTipoServicio(req);
        BigDecimal montoHabitacion = buscarPrecio(proveedor.getId(), tipoHabitacion);
        total = total.add(montoHabitacion);
        log.info("[ServicioMonto] Habitación: {} - {} = S/ {}",
                proveedor.getNombre(), tipoHabitacion, montoHabitacion);

        // 2. Servicios de alimentación del hotel
        total = total.add(calcularMontoRestaurante(proveedor, req, "HOTEL_"));

        log.info("[ServicioMonto] Total HOTEL {}: S/ {}", proveedor.getNombre(), total);
        return total;
    }

    private BigDecimal calcularMontoRestaurante(Proveedor proveedor,
                                                ServicioAsignadoRequest req,
                                                String prefijo) {
        BigDecimal total = BigDecimal.ZERO;

        if (Boolean.TRUE.equals(req.desayuno())) {
            BigDecimal m = buscarPrecio(proveedor.getId(), prefijo + "DESAYUNO");
            if (m.compareTo(BigDecimal.ZERO) > 0) {
                log.info("[ServicioMonto]   + DESAYUNO: S/ {}", m);
                total = total.add(m);
            }
        }
        if (Boolean.TRUE.equals(req.almuerzo())) {
            BigDecimal m = buscarPrecio(proveedor.getId(), prefijo + "ALMUERZO");
            if (m.compareTo(BigDecimal.ZERO) > 0) {
                log.info("[ServicioMonto]   + ALMUERZO: S/ {}", m);
                total = total.add(m);
            }
        }
        if (Boolean.TRUE.equals(req.cena())) {
            BigDecimal m = buscarPrecio(proveedor.getId(), prefijo + "CENA");
            if (m.compareTo(BigDecimal.ZERO) > 0) {
                log.info("[ServicioMonto]   + CENA: S/ {}", m);
                total = total.add(m);
            }
        }
        if (Boolean.TRUE.equals(req.snack())) {
            BigDecimal m = buscarPrecio(proveedor.getId(), prefijo + "SNACK");
            if (m.compareTo(BigDecimal.ZERO) > 0) {
                log.info("[ServicioMonto]   + SNACK: S/ {}", m);
                total = total.add(m);
            }
        }

        log.info("[ServicioMonto] Total prefijo {}: S/ {}", prefijo, total);
        return total;
    }

    /**
     * Determina la clave de servicio a consultar en ServicioProveedor
     * según el tipo de detalle y los atributos del request.
     */
    private String determinarTipoServicio(ServicioAsignadoRequest req) {
        return switch (req.tipoDetalle()) {
            case HOTEL -> {
                if (req.tipoHabitacion() == null) yield "HABITACION_SIMPLE";
                yield switch (req.tipoHabitacion().toUpperCase()) {
                    case "SIMPLE"       -> "HABITACION_SIMPLE";
                    case "DOBLE"        -> "HABITACION_DOBLE";
                    case "MATRIMONIAL"  -> "HABITACION_MATRIMONIAL";
                    default             -> "HABITACION_" + req.tipoHabitacion().toUpperCase();
                };
            }
            case TRANSPORTE -> {
                if (req.tipoTransporte() == null) yield "TRANSPORTE_INDIVIDUAL";
                yield switch (req.tipoTransporte().toUpperCase()) {
                    case "INDIVIDUAL" -> "TRANSPORTE_INDIVIDUAL";
                    case "GRUPAL"     -> "TRANSPORTE_GRUPAL";
                    default           -> "TRANSPORTE_" + req.tipoTransporte().toUpperCase();
                };
            }
            case RESTAURANTE -> "RESTAURANTE";
            default          -> req.tipoDetalle().name();
        };
    }
}

