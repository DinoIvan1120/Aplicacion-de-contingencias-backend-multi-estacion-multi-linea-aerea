package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.dto.request.ReporteFilterRequest;
import com.saasa.contingencias.domain.enumeration.*;
import com.saasa.contingencias.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test de integración con base de datos H2 en memoria (misma configuración
 * que src/test/resources/application.properties, con ddl-auto=create-drop).
 *
 * Verifica el comportamiento REAL de las Specification JPA, que no se puede
 * probar con mocks de Mockito porque construyen predicados de CriteriaBuilder
 * que solo cobran sentido contra un EntityManager real.
 */
@DataJpaTest
class AtencionSpecificationIntegrationTest {

    @Autowired private AtencionRepository atencionRepository;
    @Autowired private jakarta.persistence.EntityManager em;

    private Usuario usuario;
    private Vuelo vuelo;
    private RegistroVueloDiario registro;
    private Proveedor proveedorTransporte;
    private VueloRecurso recursoTransporte;

    @BeforeEach
    void setUp() {
        usuario = Usuario.builder()
                .nombre("Ana").apellido("Ruiz")
                .correo("ana@saasa.com").documento("12345678")
                .codigoEmpleado("EMP-001").passwordHash("hash")
                .rol(RolEnum.ADMINISTRADOR).estado(1)
                .build();
        em.persist(usuario);

        vuelo = Vuelo.builder()
                .aerolinea("Plus Ultra").codigoVuelo("PU301")
                .origen("LIM").destino("MAD")
                .fechaVuelo(LocalDate.of(2026, 8, 4))
                .tipoContingencia(ContingenciaEnum.CANCELACION)
                .estado(EstadoVueloEnum.ACTIVO)
                .creadoPor(usuario)
                .build();
        em.persist(vuelo);

        registro = RegistroVueloDiario.builder()
                .vueloItinerario(vuelo).registradoPor(usuario)
                .fechaRegistro(LocalDate.of(2026, 8, 4))
                .registradoEn(LocalDateTime.now())
                .active(true)
                .build();
        em.persist(registro);

        proveedorTransporte = Proveedor.builder()
                .tipo(TipoProveedorEnum.TRANSPORTE)
                .nombre("Taxi Express").ruc("20123456789")
                .estado(1)
                .build();
        em.persist(proveedorTransporte);

        recursoTransporte = VueloRecurso.builder()
                .vuelo(vuelo).proveedor(proveedorTransporte)
                .registroVueloDiario(registro)
                .capacidadTotal(10)
                .habilitadoPor(usuario).habilitadoEn(LocalDateTime.now())
                .estado(1)
                .build();
        em.persist(recursoTransporte);
    }

    private Atencion crearAtencion(String correlativo, EstadoAtencionEnum estado) {
        Atencion a = Atencion.builder()
                .numeroCorrelativo(correlativo)
                .vuelo(vuelo)
                .nombre("Juan").apellido("Perez")
                .pnr("ABC123").correo("jp@test.com")
                .montoTotal(BigDecimal.TEN)
                .estado(estado)
                .atendidoPor(usuario)
                .build();
        em.persist(a);
        return a;
    }

    private void asignarServicioTransporte(Atencion atencion) {
        ServicioAsignado sa = ServicioAsignado.builder()
                .atencion(atencion)
                .vueloRecurso(recursoTransporte)
                .tipoDetalle(TipoDetalleEnum.TRANSPORTE)
                .cantidad(1)
                .montoUnitario(BigDecimal.TEN)
                .montoSubtotal(BigDecimal.TEN)
                .asignadoEn(LocalDateTime.now())
                .build();
        em.persist(sa);
    }

    // ══════════════════════════════════════════════════════════════
    // LINEA_AEREA nunca debe ver anulados
    // ══════════════════════════════════════════════════════════════

    @Test
    void lineaAerea_nuncaVeAtencionesAnuladas() {
        crearAtencion("SGC-000000001", EstadoAtencionEnum.ACTIVO);
        crearAtencion("SGC-000000002", EstadoAtencionEnum.ANULADO);
        em.flush();

        var spec = AtencionSpecification.build(null, "LINEA_AEREA", "Plus Ultra", null);
        List<Atencion> resultado = atencionRepository.findAll(spec);

        assertEquals(1, resultado.size());
        assertEquals("SGC-000000001", resultado.get(0).getNumeroCorrelativo());
    }

    @Test
    void lineaAerea_conFiltroEstadoAnuladoForzado_noDevuelveNada() {
        // Intento malicioso: forzar estado=ANULADO desde el cliente
        crearAtencion("SGC-000000003", EstadoAtencionEnum.ACTIVO);
        crearAtencion("SGC-000000004", EstadoAtencionEnum.ANULADO);
        em.flush();

        var filtros = new ReporteFilterRequest(
                null, null, null, null, null, null, null, null, null, null, "ANULADO");
        var spec = AtencionSpecification.build(filtros, "LINEA_AEREA", "Plus Ultra", null);
        List<Atencion> resultado = atencionRepository.findAll(spec);

        assertTrue(resultado.isEmpty(),
                "El AND obligatorio debe anular cualquier intento de ver anulados");
    }

    // ══════════════════════════════════════════════════════════════
    // PROVEEDOR nunca debe ver anulados (aunque el servicio sí sea suyo)
    // ══════════════════════════════════════════════════════════════

    @Test
    void proveedor_veSusPropiasAtencionesActivas_peroNuncaLasAnuladas() {
        Atencion activa = crearAtencion("SGC-000000005", EstadoAtencionEnum.ACTIVO);
        Atencion anulada = crearAtencion("SGC-000000006", EstadoAtencionEnum.ANULADO);
        asignarServicioTransporte(activa);
        asignarServicioTransporte(anulada);
        em.flush();

        var spec = AtencionSpecification.build(
                null, "PROVEEDOR", null, proveedorTransporte.getId());
        List<Atencion> resultado = atencionRepository.findAll(spec);

        assertEquals(1, resultado.size());
        assertEquals("SGC-000000005", resultado.get(0).getNumeroCorrelativo());
    }

    // ══════════════════════════════════════════════════════════════
    // Roles internos SÍ ven anulados (comportamiento sin cambios)
    // ══════════════════════════════════════════════════════════════

    @Test
    void administrador_veTodasIncluyendoAnuladas() {
        crearAtencion("SGC-000000007", EstadoAtencionEnum.ACTIVO);
        crearAtencion("SGC-000000008", EstadoAtencionEnum.ANULADO);
        em.flush();

        var spec = AtencionSpecification.build(null, "ADMINISTRADOR", null, null);
        List<Atencion> resultado = atencionRepository.findAll(spec);

        assertEquals(2, resultado.size(),
                "Administrador no debe verse afectado por la restricción nueva");
    }

    @Test
    void administrador_puedeFiltrarExplicitamentePorEstadoActivo() {
        crearAtencion("SGC-000000009", EstadoAtencionEnum.ACTIVO);
        crearAtencion("SGC-000000010", EstadoAtencionEnum.ANULADO);
        em.flush();

        var filtros = new ReporteFilterRequest(
                null, null, null, null, null, null, null, null, null, null, "ACTIVO");
        var spec = AtencionSpecification.build(filtros, "ADMINISTRADOR", null, null);
        List<Atencion> resultado = atencionRepository.findAll(spec);

        assertEquals(1, resultado.size());
        assertEquals(EstadoAtencionEnum.ACTIVO, resultado.get(0).getEstado());
    }
}
