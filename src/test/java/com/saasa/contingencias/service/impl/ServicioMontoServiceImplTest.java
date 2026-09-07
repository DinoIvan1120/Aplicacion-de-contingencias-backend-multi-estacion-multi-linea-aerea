package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.domain.dto.request.ServicioAsignadoRequest;
import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import com.saasa.contingencias.domain.model.Proveedor;
import com.saasa.contingencias.domain.model.ServicioProveedor;
import com.saasa.contingencias.domain.model.VueloRecurso;
import com.saasa.contingencias.domain.repository.ServicioProveedorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test unitario de ServicioMontoServiceImpl — cálculo de precios de
 * servicios de proveedores (hotel, transporte, restaurante).
 *
 * Es el servicio con más riesgo silencioso del sistema: un error aquí
 * no lanza ninguna excepción visible, simplemente calcula mal el monto
 * que se factura o reporta. Por eso el foco de estos tests es verificar
 * la CLAVE exacta ("HABITACION_DOBLE", "HOTEL_DESAYUNO", etc.) que se
 * consulta en ServicioProveedorRepository para cada combinación de
 * entrada, no solo que el método "no explote".
 */
@ExtendWith(MockitoExtension.class)
class ServicioMontoServiceImplTest {

    @Mock ServicioProveedorRepository servicioProveedorRepository;

    private ServicioMontoServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new ServicioMontoServiceImpl(servicioProveedorRepository);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers de construcción
    // ══════════════════════════════════════════════════════════════════════

    private Proveedor proveedor(Long id, String nombre) {
        return Proveedor.builder().id(id).nombre(nombre).build();
    }

    private VueloRecurso recurso(Proveedor p) {
        return VueloRecurso.builder().id(1L).proveedor(p).build();
    }

    private ServicioProveedor precio(BigDecimal monto, int estado) {
        return ServicioProveedor.builder().monto(monto).estado(estado).build();
    }

    private ServicioAsignadoRequest reqHotel(String tipoHabitacion, boolean desayuno,
                                             boolean almuerzo, boolean cena, boolean snack) {
        return new ServicioAsignadoRequest(
                1L, TipoDetalleEnum.HOTEL, tipoHabitacion,
                desayuno, almuerzo, cena, snack,
                LocalDate.now(), LocalDate.now().plusDays(1),
                null, 1);
    }

    private ServicioAsignadoRequest reqTransporte(String tipoTransporte) {
        return new ServicioAsignadoRequest(
                1L, TipoDetalleEnum.TRANSPORTE, null,
                null, null, null, null,
                null, null,
                tipoTransporte, 1);
    }

    private ServicioAsignadoRequest reqRestaurante(boolean desayuno, boolean almuerzo,
                                                   boolean cena, boolean snack) {
        return new ServicioAsignadoRequest(
                1L, TipoDetalleEnum.RESTAURANTE, null,
                desayuno, almuerzo, cena, snack,
                null, null,
                null, 1);
    }

    // ══════════════════════════════════════════════════════════════════════
    // buscarPrecio — comportamiento base usado por todo lo demás
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("buscarPrecio — servicio no existe → retorna ZERO")
    void buscarPrecio_servicioNoExiste_retornaZero() {
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(1L, "HABITACION_SIMPLE"))
                .thenReturn(Optional.empty());

        BigDecimal resultado = service.buscarPrecio(1L, "HABITACION_SIMPLE");

        assertEquals(0, BigDecimal.ZERO.compareTo(resultado));
    }

    @Test
    @DisplayName("buscarPrecio — servicio inactivo (estado != 1) → retorna ZERO, no el monto guardado")
    void buscarPrecio_servicioInactivo_retornaZero() {
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(1L, "HABITACION_DOBLE"))
                .thenReturn(Optional.of(precio(new BigDecimal("200.00"), 0)));

        BigDecimal resultado = service.buscarPrecio(1L, "HABITACION_DOBLE");

        assertEquals(0, BigDecimal.ZERO.compareTo(resultado));
    }

    @Test
    @DisplayName("buscarPrecio — servicio con estado null → se trata como inactivo, retorna ZERO")
    void buscarPrecio_estadoNull_retornaZero() {
        ServicioProveedor sp = ServicioProveedor.builder()
                .monto(new BigDecimal("100.00")).estado(null).build();
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(1L, "TRANSPORTE_GRUPAL"))
                .thenReturn(Optional.of(sp));

        BigDecimal resultado = service.buscarPrecio(1L, "TRANSPORTE_GRUPAL");

        assertEquals(0, BigDecimal.ZERO.compareTo(resultado));
    }

    @Test
    @DisplayName("buscarPrecio — servicio activo → retorna el monto guardado")
    void buscarPrecio_servicioActivo_retornaMonto() {
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(1L, "RESTAURANTE"))
                .thenReturn(Optional.of(precio(new BigDecimal("45.50"), 1)));

        BigDecimal resultado = service.buscarPrecio(1L, "RESTAURANTE");

        assertEquals(0, new BigDecimal("45.50").compareTo(resultado));
    }

    // ══════════════════════════════════════════════════════════════════════
    // calcularMonto — HOTEL: mapeo de tipoHabitacion → clave correcta
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("HOTEL — tipoHabitacion SIMPLE → consulta clave HABITACION_SIMPLE")
    void calcularMonto_hotelSimple_consultaClaveCorrecta() {
        Proveedor p = proveedor(1L, "Hotel Costa del Sol");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(1L, "HABITACION_SIMPLE"))
                .thenReturn(Optional.of(precio(new BigDecimal("120.00"), 1)));

        BigDecimal monto = service.calcularMonto(r, reqHotel("SIMPLE", false, false, false, false));

        assertEquals(0, new BigDecimal("120.00").compareTo(monto));
    }

    @Test
    @DisplayName("HOTEL — tipoHabitacion en minúsculas ('doble') → se normaliza a HABITACION_DOBLE")
    void calcularMonto_hotelTipoHabitacionMinusculas_normalizaCorrectamente() {
        Proveedor p = proveedor(1L, "Hotel Costa del Sol");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(1L, "HABITACION_DOBLE"))
                .thenReturn(Optional.of(precio(new BigDecimal("180.00"), 1)));

        BigDecimal monto = service.calcularMonto(r, reqHotel("doble", false, false, false, false));

        assertEquals(0, new BigDecimal("180.00").compareTo(monto));
    }

    @Test
    @DisplayName("HOTEL — tipo de habitación no estándar (custom) → usa prefijo HABITACION_ + valor")
    void calcularMonto_hotelTipoHabitacionCustom_usaPrefijoGenerico() {
        Proveedor p = proveedor(1L, "Hotel Costa del Sol");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(1L, "HABITACION_SUITE"))
                .thenReturn(Optional.of(precio(new BigDecimal("350.00"), 1)));

        BigDecimal monto = service.calcularMonto(r, reqHotel("suite", false, false, false, false));

        assertEquals(0, new BigDecimal("350.00").compareTo(monto));
    }

    @Test
    @DisplayName("HOTEL — habitación + desayuno + cena → suma habitación y ambas comidas con prefijo HOTEL_")
    void calcularMonto_hotelConDesayunoYCena_sumaTodo() {
        Proveedor p = proveedor(1L, "Hotel Costa del Sol");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(1L, "HABITACION_MATRIMONIAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("200.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(1L, "HOTEL_DESAYUNO"))
                .thenReturn(Optional.of(precio(new BigDecimal("25.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(1L, "HOTEL_CENA"))
                .thenReturn(Optional.of(precio(new BigDecimal("35.00"), 1)));

        BigDecimal monto = service.calcularMonto(r,
                reqHotel("MATRIMONIAL", true, false, true, false));

        // 200.00 (habitación) + 25.00 (desayuno) + 35.00 (cena) = 260.00
        assertEquals(0, new BigDecimal("260.00").compareTo(monto));
        verify(servicioProveedorRepository, never())
                .findByProveedorIdAndTipoServicio(1L, "HOTEL_ALMUERZO");
        verify(servicioProveedorRepository, never())
                .findByProveedorIdAndTipoServicio(1L, "HOTEL_SNACK");
    }

    @Test
    @DisplayName("HOTEL — comida marcada pero sin precio configurado (ZERO) → no se suma al total")
    void calcularMonto_hotelComidaSinPrecioConfigurado_noSumaAlTotal() {
        Proveedor p = proveedor(1L, "Hotel Costa del Sol");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(1L, "HABITACION_SIMPLE"))
                .thenReturn(Optional.of(precio(new BigDecimal("100.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(1L, "HOTEL_ALMUERZO"))
                .thenReturn(Optional.empty()); // sin precio configurado

        BigDecimal monto = service.calcularMonto(r,
                reqHotel("SIMPLE", false, true, false, false));

        // Solo la habitación; el almuerzo sin precio no se suma (queda en 0)
        assertEquals(0, new BigDecimal("100.00").compareTo(monto));
    }

    // ══════════════════════════════════════════════════════════════════════
    // calcularMonto — RESTAURANTE
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("RESTAURANTE — desayuno + almuerzo + cena + snack → suma las 4 con prefijo RESTAURANTE_")
    void calcularMonto_restauranteTodasLasComidas_sumaCorrectamente() {
        Proveedor p = proveedor(2L, "Restaurante El Buen Sabor");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(2L, "RESTAURANTE_DESAYUNO"))
                .thenReturn(Optional.of(precio(new BigDecimal("15.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(2L, "RESTAURANTE_ALMUERZO"))
                .thenReturn(Optional.of(precio(new BigDecimal("20.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(2L, "RESTAURANTE_CENA"))
                .thenReturn(Optional.of(precio(new BigDecimal("18.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(2L, "RESTAURANTE_SNACK"))
                .thenReturn(Optional.of(precio(new BigDecimal("8.00"), 1)));

        BigDecimal monto = service.calcularMonto(r, reqRestaurante(true, true, true, true));

        // 15 + 20 + 18 + 8 = 61.00
        assertEquals(0, new BigDecimal("61.00").compareTo(monto));
    }

    @Test
    @DisplayName("RESTAURANTE — sin ninguna comida marcada → monto ZERO, no consulta ningún precio")
    void calcularMonto_restauranteSinComidas_retornaZero() {
        Proveedor p = proveedor(2L, "Restaurante El Buen Sabor");
        VueloRecurso r = recurso(p);

        BigDecimal monto = service.calcularMonto(r, reqRestaurante(false, false, false, false));

        assertEquals(0, BigDecimal.ZERO.compareTo(monto));
        verifyNoInteractions(servicioProveedorRepository);
    }

    // ══════════════════════════════════════════════════════════════════════
    // calcularMonto — TRANSPORTE
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TRANSPORTE — tipoTransporte INDIVIDUAL → consulta clave TRANSPORTE_INDIVIDUAL")
    void calcularMonto_transporteIndividual_consultaClaveCorrecta() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));

        BigDecimal monto = service.calcularMonto(r, reqTransporte("INDIVIDUAL"));

        assertEquals(0, new BigDecimal("30.00").compareTo(monto));
    }

    @Test
    @DisplayName("TRANSPORTE — tipoTransporte GRUPAL → consulta clave TRANSPORTE_GRUPAL")
    void calcularMonto_transporteGrupal_consultaClaveCorrecta() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_GRUPAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("80.00"), 1)));

        BigDecimal monto = service.calcularMonto(r, reqTransporte("GRUPAL"));

        assertEquals(0, new BigDecimal("80.00").compareTo(monto));
    }

    @Test
    @DisplayName("TRANSPORTE — sin tipoTransporte (null) → por defecto usa TRANSPORTE_INDIVIDUAL")
    void calcularMonto_transporteSinTipo_usaIndividualPorDefecto() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));

        BigDecimal monto = service.calcularMonto(r, reqTransporte(null));

        assertEquals(0, new BigDecimal("30.00").compareTo(monto));
    }

    @Test
    @DisplayName("TRANSPORTE — sin precio configurado → retorna ZERO sin lanzar excepción")
    void calcularMonto_transporteSinPrecioConfigurado_retornaZero() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.empty());

        BigDecimal monto = service.calcularMonto(r, reqTransporte("INDIVIDUAL"));

        assertEquals(0, BigDecimal.ZERO.compareTo(monto));
    }

    // ══════════════════════════════════════════════════════════════════════
    // calcularMonto — TRANSPORTE AMBOS (nuevo: Aeropuerto-Domicilio + Domicilio-Aeropuerto)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TRANSPORTE — tipoTransporte AMBOS → suma TRANSPORTE_INDIVIDUAL + TRANSPORTE_GRUPAL")
    void calcularMonto_transporteAmbos_sumaAmbosPrecios() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_GRUPAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("80.00"), 1)));

        BigDecimal monto = service.calcularMonto(r, reqTransporte("AMBOS"));

        // 30.00 + 80.00 = 110.00
        assertEquals(0, new BigDecimal("110.00").compareTo(monto));
    }

    @Test
    @DisplayName("TRANSPORTE — tipoTransporte 'ambos' en minúsculas → se normaliza igual y suma ambos precios")
    void calcularMonto_transporteAmbosMinusculas_seNormalizaYSuma() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_GRUPAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("80.00"), 1)));

        BigDecimal monto = service.calcularMonto(r, reqTransporte("ambos"));

        assertEquals(0, new BigDecimal("110.00").compareTo(monto));
    }

    @Test
    @DisplayName("TRANSPORTE — AMBOS con solo un precio configurado → suma solo el que existe (el otro es ZERO)")
    void calcularMonto_transporteAmbosConUnPrecioFaltante_sumaSoloElConfigurado() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_GRUPAL"))
                .thenReturn(Optional.empty()); // sin precio configurado

        BigDecimal monto = service.calcularMonto(r, reqTransporte("AMBOS"));

        assertEquals(0, new BigDecimal("30.00").compareTo(monto));
    }

    @Test
    @DisplayName("TRANSPORTE — AMBOS sin ningún precio configurado → retorna ZERO sin lanzar excepción")
    void calcularMonto_transporteAmbosSinPreciosConfigurados_retornaZero() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.empty());
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_GRUPAL"))
                .thenReturn(Optional.empty());

        BigDecimal monto = service.calcularMonto(r, reqTransporte("AMBOS"));

        assertEquals(0, BigDecimal.ZERO.compareTo(monto));
    }

    @Test
    @DisplayName("TRANSPORTE — AMBOS con precio GRUPAL inactivo (estado != 1) → no lo suma, solo INDIVIDUAL")
    void calcularMonto_transporteAmbosConGrupalInactivo_noSumaElInactivo() {
        Proveedor p = proveedor(3L, "Taxi Express");
        VueloRecurso r = recurso(p);
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_INDIVIDUAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("30.00"), 1)));
        when(servicioProveedorRepository.findByProveedorIdAndTipoServicio(3L, "TRANSPORTE_GRUPAL"))
                .thenReturn(Optional.of(precio(new BigDecimal("80.00"), 0))); // inactivo

        BigDecimal monto = service.calcularMonto(r, reqTransporte("AMBOS"));

        assertEquals(0, new BigDecimal("30.00").compareTo(monto));
    }
}
