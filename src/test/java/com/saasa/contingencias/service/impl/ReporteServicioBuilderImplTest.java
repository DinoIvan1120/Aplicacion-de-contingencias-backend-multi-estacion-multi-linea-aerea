package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.domain.dto.request.ActualizarServiciosRequest;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.model.Proveedor;
import com.saasa.contingencias.domain.model.ServicioAsignado;
import com.saasa.contingencias.domain.model.ServicioProveedor;
import com.saasa.contingencias.domain.model.VueloRecurso;
import com.saasa.contingencias.domain.repository.ServicioAsignadoRepository;
import com.saasa.contingencias.domain.repository.ServicioProveedorRepository;
import com.saasa.contingencias.domain.repository.VueloRecursoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test unitario de ReporteServicioBuilderImpl — específicamente
 * crearServicioTransporte(...), usado al editar los servicios de un
 * voucher desde el módulo de Reportes.
 *
 * Cubre el mismo contrato de precios que ServicioMontoServiceImplTest,
 * pero en la ruta de "edición desde Reportes" (ActualizarServiciosRequest),
 * incluyendo el nuevo caso "AMBOS" (Aeropuerto-Domicilio + Domicilio-Aeropuerto).
 */
@ExtendWith(MockitoExtension.class)
class ReporteServicioBuilderImplTest {

    @Mock VueloRecursoRepository vueloRecursoRepository;
    @Mock ServicioAsignadoRepository servicioAsignadoRepository;
    @Mock ServicioProveedorRepository servicioProveedorRepository;
    @Mock com.saasa.contingencias.service.IDisponibilidadService disponibilidadService;

    private ReporteServicioBuilderImpl service;

    @BeforeEach
    void setUp() {
        service = new ReporteServicioBuilderImpl(
                vueloRecursoRepository, servicioAsignadoRepository, servicioProveedorRepository,
                disponibilidadService);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private Proveedor proveedor(Long id, String nombre) {
        return Proveedor.builder().id(id).nombre(nombre).build();
    }

    private VueloRecurso recurso(Long id, Proveedor p) {
        return VueloRecurso.builder().id(id).proveedor(p).build();
    }

    private ServicioProveedor precio(BigDecimal monto, int estado) {
        return ServicioProveedor.builder().monto(monto).estado(estado).build();
    }

    private ActualizarServiciosRequest reqTransporte(Long vueloRecursoId, String tipoTransporte, int cantidad) {
        return new ActualizarServiciosRequest(
                null, null, null, null, null, null, null, null, null,
                vueloRecursoId, tipoTransporte, cantidad,
                null, null, null, null, null, null);
    }

    // ══════════════════════════════════════════════════════════════════════
    // crearServicioTransporte — INDIVIDUAL / GRUPAL (comportamiento existente)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("crearServicioTransporte — INDIVIDUAL → usa solo el precio TRANSPORTE_INDIVIDUAL (precio fijo, sin multiplicar por pasajeros)")
    void crearServicioTransporte_individual_usaSoloPrecioIndividual() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(10L, p);
        Atencion a = Atencion.builder().id(1L).build();

        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(r));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));

        BigDecimal monto = service.crearServicioTransporte(a, reqTransporte(10L, "INDIVIDUAL", 2));

        // El transporte es precio FIJO por el servicio (no por pasajero) — igual
        // que en la asignación original de servicios (AtencionServiceImpl).
        // 2 pasajeros no debe multiplicar: sigue siendo 30.00.
        assertEquals(0, new BigDecimal("30.00").compareTo(monto));
        verify(servicioProveedorRepository, never())
                .findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_GRUPAL");
    }

    @Test
    @DisplayName("crearServicioTransporte — GRUPAL → usa solo el precio TRANSPORTE_GRUPAL (precio fijo, sin multiplicar por pasajeros)")
    void crearServicioTransporte_grupal_usaSoloPrecioGrupal() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(10L, p);
        Atencion a = Atencion.builder().id(1L).build();

        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(r));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_GRUPAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("80.00"), 1)));

        BigDecimal monto = service.crearServicioTransporte(a, reqTransporte(10L, "GRUPAL", 4));

        // Precio fijo: 4 pasajeros no debe multiplicar, sigue siendo 80.00.
        assertEquals(0, new BigDecimal("80.00").compareTo(monto));
        verify(servicioProveedorRepository, never())
                .findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL");
    }

    // ══════════════════════════════════════════════════════════════════════
    // crearServicioTransporte — AMBOS (nuevo)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("crearServicioTransporte — AMBOS → suma TRANSPORTE_INDIVIDUAL + TRANSPORTE_GRUPAL")
    void crearServicioTransporte_ambos_sumaAmbosPrecios() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(10L, p);
        Atencion a = Atencion.builder().id(1L).build();

        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(r));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_GRUPAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("80.00"), 1)));

        BigDecimal monto = service.crearServicioTransporte(a, reqTransporte(10L, "AMBOS", 1));

        // 30.00 + 80.00 = 110.00 — precio fijo del servicio (no se multiplica
        // por la cantidad de pasajeros).
        assertEquals(0, new BigDecimal("110.00").compareTo(monto));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Regresión — bug: al editar los servicios de un voucher grupal con 2+
    // pasajeros desde el Reporte, el transporte se duplicaba (o más) porque
    // se multiplicaba por la cantidad de pasajeros. La asignación ORIGINAL de
    // servicios (AtencionServiceImpl.asignarServicios) trata el transporte
    // como un precio FIJO por el servicio/vehículo, sin multiplicar por
    // cantidad — esta clase debía seguir la misma regla y no lo hacía.
    //
    // Caso real reportado: transporte AMBOS con 2 pasajeros, S/ 215 al
    // registrar el grupo. Al editar servicios desde el reporte para el
    // acompañante, el monto pasaba a S/ 430 (exactamente el doble) aunque
    // el agente solo había quitado un ítem del hotel.
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("crearServicioTransporte — AMBOS con 2+ pasajeros → NO multiplica por cantidad (precio fijo)")
    void crearServicioTransporte_ambosConVariosPasajeros_noMultiplicaPorCantidad() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(10L, p);
        Atencion a = Atencion.builder().id(1L).build();

        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(r));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_GRUPAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("80.00"), 1)));

        BigDecimal monto = service.crearServicioTransporte(a, reqTransporte(10L, "AMBOS", 2));

        // (30.00 + 80.00) = 110.00, SIN multiplicar por los 2 pasajeros.
        // Si el bug se reintrodujera, este assert fallaría con 220.00.
        assertEquals(0, new BigDecimal("110.00").compareTo(monto));
    }

    @Test
    @DisplayName("crearServicioTransporte — AMBOS con un solo precio configurado → suma solo el que existe (el otro es ZERO)")
    void crearServicioTransporte_ambosConUnPrecioFaltante_sumaSoloElConfigurado() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(10L, p);
        Atencion a = Atencion.builder().id(1L).build();

        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(r));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_GRUPAL"))
                .thenReturn(Optional.empty()); // sin precio configurado

        BigDecimal monto = service.crearServicioTransporte(a, reqTransporte(10L, "AMBOS", 1));

        // Solo el precio individual (30.00); grupal no configurado suma 0
        assertEquals(0, new BigDecimal("30.00").compareTo(monto));
    }

    @Test
    @DisplayName("crearServicioTransporte — AMBOS en minúsculas → se normaliza igual que INDIVIDUAL/GRUPAL")
    void crearServicioTransporte_ambosMinusculas_seNormalizaCorrectamente() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(10L, p);
        Atencion a = Atencion.builder().id(1L).build();

        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(r));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_GRUPAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("80.00"), 1)));

        BigDecimal monto = service.crearServicioTransporte(a, reqTransporte(10L, "ambos", 1));

        assertEquals(0, new BigDecimal("110.00").compareTo(monto));
    }

    @Test
    @DisplayName("crearServicioTransporte — AMBOS → guarda un único ServicioAsignado con tipoTransporte=AMBOS")
    void crearServicioTransporte_ambos_guardaUnUnicoServicioAsignado() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(10L, p);
        Atencion a = Atencion.builder().id(1L).build();

        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(r));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_GRUPAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("80.00"), 1)));

        service.crearServicioTransporte(a, reqTransporte(10L, "AMBOS", 3));

        ArgumentCaptor<ServicioAsignado> captor = ArgumentCaptor.forClass(ServicioAsignado.class);
        // Se guarda una sola vez → no se duplica la fila (clave para no romper
        // disponibilidad, reporte de detalle ni el PDF, que esperan un único
        // ServicioAsignado de tipo TRANSPORTE por atención).
        verify(servicioAsignadoRepository, times(1)).save(captor.capture());

        ServicioAsignado guardado = captor.getValue();
        assertEquals("AMBOS", guardado.getTipoTransporte());
        // "cantidad" sigue guardando el número de pasajeros (para mostrar
        // "3 pax" en el PDF/reporte), pero el monto es fijo — NO se
        // multiplica por esa cantidad (ver test de regresión más arriba).
        assertEquals(3, guardado.getCantidad());
        assertEquals(0, new BigDecimal("110.00").compareTo(guardado.getMontoUnitario()));
        assertEquals(0, new BigDecimal("110.00").compareTo(guardado.getMontoSubtotal()));
    }
    // ══════════════════════════════════════════════════════════════════════
    // Disponibilidad + lock pesimista (NUEVO — cierra el hueco que tenía
    // este flujo de "editar servicios desde Reportes": antes no validaba
    // disponibilidad en absoluto y usaba findById sin bloqueo).
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("crearServicioTransporte — usa findByIdForUpdate (lock pesimista), NO findById")
    void crearServicioTransporte_usaFindByIdForUpdate() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(10L, p);
        Atencion a = Atencion.builder().id(1L).build();

        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(r));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));

        service.crearServicioTransporte(a, reqTransporte(10L, "INDIVIDUAL", 1));

        verify(vueloRecursoRepository).findByIdForUpdate(10L);
        verify(vueloRecursoRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("crearServicioTransporte — disponibilidad insuficiente → lanza excepción y NO guarda")
    void crearServicioTransporte_disponibilidadInsuficiente_noGuarda() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(10L, p);
        Atencion a = Atencion.builder().id(1L).build();

        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(r));
        doThrow(new com.saasa.contingencias.config.exception.BadRequestException(
                "Capacidad de transporte insuficiente"))
                .when(disponibilidadService).validarDisponibilidadGeneral(r, 2);

        assertThrows(com.saasa.contingencias.config.exception.BadRequestException.class,
                () -> service.crearServicioTransporte(a, reqTransporte(10L, "INDIVIDUAL", 2)));

        verify(servicioAsignadoRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearServicioTransporte — cantidadPasajeros null → valida y guarda con 1 (no NullPointerException)")
    void crearServicioTransporte_cantidadNull_defaultUno() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(10L, p);
        Atencion a = Atencion.builder().id(1L).build();

        when(vueloRecursoRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(r));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));

        // cantidad null en el request (el DTO no exige @NotNull, solo @Min(1))
        ActualizarServiciosRequest reqSinCantidad = new ActualizarServiciosRequest(
                null, null, null, null, null, null, null, null, null,
                10L, "INDIVIDUAL", null,
                null, null, null, null, null, null);

        service.crearServicioTransporte(a, reqSinCantidad);

        verify(disponibilidadService).validarDisponibilidadGeneral(r, 1);
        ArgumentCaptor<ServicioAsignado> captor = ArgumentCaptor.forClass(ServicioAsignado.class);
        verify(servicioAsignadoRepository).save(captor.capture());
        assertEquals(1, captor.getValue().getCantidad());
    }

    // ══════════════════════════════════════════════════════════════════════
    // crearServicioHotel (NUEVO — el archivo original no tenía ningún test
    // para este método)
    // ══════════════════════════════════════════════════════════════════════

    private ActualizarServiciosRequest reqHotel(Long vueloRecursoId, String tipoHabitacion, Integer cantidad) {
        return new ActualizarServiciosRequest(
                vueloRecursoId, tipoHabitacion, cantidad,
                false, false, false, false,
                java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(1),
                null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    @DisplayName("crearServicioHotel — valida disponibilidad y guarda cantidad correcta")
    void crearServicioHotel_flujoExitoso_validaYGuarda() {
        Proveedor p = proveedor(4L, "Hotel Costa del Sol");
        VueloRecurso r = recurso(20L, p);
        Atencion a = Atencion.builder().id(2L).build();

        when(vueloRecursoRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(r));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(4L, "HABITACION_DOBLE"))
                .thenReturn(Optional.of(precio(new BigDecimal("100.00"), 1)));

        BigDecimal monto = service.crearServicioHotel(a, reqHotel(20L, "DOBLE", 3));

        verify(disponibilidadService).validarDisponibilidadHotel(r, "DOBLE", 3);
        assertEquals(0, new BigDecimal("300.00").compareTo(monto)); // 100 x 3

        ArgumentCaptor<ServicioAsignado> captor = ArgumentCaptor.forClass(ServicioAsignado.class);
        verify(servicioAsignadoRepository).save(captor.capture());
        assertEquals(3, captor.getValue().getCantidad());
    }

    @Test
    @DisplayName("crearServicioHotel — disponibilidad insuficiente → lanza excepción y NO guarda")
    void crearServicioHotel_disponibilidadInsuficiente_noGuarda() {
        Proveedor p = proveedor(4L, "Hotel Costa del Sol");
        VueloRecurso r = recurso(20L, p);
        Atencion a = Atencion.builder().id(2L).build();

        when(vueloRecursoRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(r));
        doThrow(new com.saasa.contingencias.config.exception.BadRequestException(
                "No hay habitaciones simples disponibles"))
                .when(disponibilidadService).validarDisponibilidadHotel(r, "SIMPLE", 1);

        assertThrows(com.saasa.contingencias.config.exception.BadRequestException.class,
                () -> service.crearServicioHotel(a, reqHotel(20L, "SIMPLE", 1)));

        verify(servicioAsignadoRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearServicioHotel — cantidadHabitaciones null → valida y guarda con 1 (regresión NPE)")
    void crearServicioHotel_cantidadNull_defaultUno_sinNPE() {
        // Regresión: antes del fix, BigDecimal.valueOf(request.cantidadHabitaciones())
        // explotaba con NullPointerException si el campo venía null desde el
        // frontend (el DTO no lo exige @NotNull).
        Proveedor p = proveedor(4L, "Hotel Costa del Sol");
        VueloRecurso r = recurso(20L, p);
        Atencion a = Atencion.builder().id(2L).build();

        when(vueloRecursoRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(r));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(4L, "HABITACION_SIMPLE"))
                .thenReturn(Optional.of(precio(new BigDecimal("80.00"), 1)));

        BigDecimal monto = assertDoesNotThrow(
                () -> service.crearServicioHotel(a, reqHotel(20L, "SIMPLE", null)));

        assertEquals(0, new BigDecimal("80.00").compareTo(monto)); // 80 x 1
        verify(disponibilidadService).validarDisponibilidadHotel(r, "SIMPLE", 1);
    }

    // ══════════════════════════════════════════════════════════════════════
    // crearServicioRestaurante (NUEVO — el archivo original no tenía ningún
    // test para este método)
    // ══════════════════════════════════════════════════════════════════════

    private ActualizarServiciosRequest reqRestaurante(Long vueloRecursoId, Integer cantidadCubiertos) {
        return new ActualizarServiciosRequest(
                null, null, null, null, null, null, null, null, null,
                null, null, null,
                vueloRecursoId,
                true, false, false, false,
                cantidadCubiertos);
    }

    @Test
    @DisplayName("crearServicioRestaurante — valida disponibilidad y guarda cantidad correcta")
    void crearServicioRestaurante_flujoExitoso_validaYGuarda() {
        Proveedor p = proveedor(5L, "Restaurante El Buen Sabor");
        VueloRecurso r = recurso(30L, p);
        Atencion a = Atencion.builder().id(3L).build();

        when(vueloRecursoRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(r));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(5L, "RESTAURANTE_DESAYUNO"))
                .thenReturn(Optional.of(precio(new BigDecimal("15.00"), 1)));

        service.crearServicioRestaurante(a, reqRestaurante(30L, 4));

        verify(disponibilidadService).validarDisponibilidadGeneral(r, 4);
        ArgumentCaptor<ServicioAsignado> captor = ArgumentCaptor.forClass(ServicioAsignado.class);
        verify(servicioAsignadoRepository).save(captor.capture());
        assertEquals(4, captor.getValue().getCantidad());
    }

    @Test
    @DisplayName("crearServicioRestaurante — disponibilidad insuficiente → lanza excepción y NO guarda")
    void crearServicioRestaurante_disponibilidadInsuficiente_noGuarda() {
        Proveedor p = proveedor(5L, "Restaurante El Buen Sabor");
        VueloRecurso r = recurso(30L, p);
        Atencion a = Atencion.builder().id(3L).build();

        when(vueloRecursoRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(r));
        doThrow(new com.saasa.contingencias.config.exception.BadRequestException(
                "Capacidad de restaurante insuficiente"))
                .when(disponibilidadService).validarDisponibilidadGeneral(r, 4);

        assertThrows(com.saasa.contingencias.config.exception.BadRequestException.class,
                () -> service.crearServicioRestaurante(a, reqRestaurante(30L, 4)));

        verify(servicioAsignadoRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearServicioRestaurante — cantidadCubiertos null → valida y guarda con 1 (no NullPointerException)")
    void crearServicioRestaurante_cantidadNull_defaultUno() {
        Proveedor p = proveedor(5L, "Restaurante El Buen Sabor");
        VueloRecurso r = recurso(30L, p);
        Atencion a = Atencion.builder().id(3L).build();

        when(vueloRecursoRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(r));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(5L, "RESTAURANTE_DESAYUNO"))
                .thenReturn(Optional.of(precio(new BigDecimal("15.00"), 1)));

        service.crearServicioRestaurante(a, reqRestaurante(30L, null));

        verify(disponibilidadService).validarDisponibilidadGeneral(r, 1);
        ArgumentCaptor<ServicioAsignado> captor = ArgumentCaptor.forClass(ServicioAsignado.class);
        verify(servicioAsignadoRepository).save(captor.capture());
        assertEquals(1, captor.getValue().getCantidad());
    }
}
