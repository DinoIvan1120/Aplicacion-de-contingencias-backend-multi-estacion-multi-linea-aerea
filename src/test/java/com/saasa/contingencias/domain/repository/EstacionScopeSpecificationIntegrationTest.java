package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.enumeration.*;
import com.saasa.contingencias.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test de integración con base de datos H2 en memoria (mismo mecanismo que
 * {@link AtencionRepositoryEstacionFiltroTest}).
 *
 * Cierra el gap de Fase 4 (Documento Funcional Multi-Estación v1.1, sección
 * "pruebas cruzadas entre estaciones") que {@code AtencionRepositoryEstacionFiltroTest}
 * NO cubre: ese test valida el filtro solo para las 6 consultas nativas de
 * {@code AtencionRepository}. Este test valida el otro mecanismo — el que
 * usan {@code VueloServiceImpl} y {@code ProveedorServiceImpl} — que es
 * {@link EstacionSpecifications#porEstacionesDelUsuario(List)} vía
 * {@code JpaSpecificationExecutor}, y que hasta ahora ningún test ejercitaba
 * contra una base de datos real (los tests de esos dos servicios solo
 * verificaban con mocks que se *llamaba* a EstacionContext, nunca que el
 * filtro realmente excluye filas).
 *
 * Escenario de datos: dos estaciones (Lima = 1L, Cusco = 2L), dos líneas
 * aéreas (LATAM habilitada en ambas estaciones, Avianca habilitada solo en
 * Lima), un vuelo y un proveedor por estación/línea combinada — para poder
 * probar también que el filtro de línea aérea (RN-802, por Specification)
 * y el filtro de estación (por sesión) se combinan correctamente sin que
 * uno tape los huecos del otro.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EstacionScopeSpecificationIntegrationTest {

    private static final Long ESTACION_LIMA = 1L;
    private static final Long ESTACION_CUSCO = 2L;

    @Autowired private VueloRepository vueloRepository;
    @Autowired private ProveedorRepository proveedorRepository;
    @Autowired private jakarta.persistence.EntityManager em;

    private Usuario usuario;
    private Long latamId;
    private Long aviancaId;

    private Vuelo vueloLima;
    private Vuelo vueloCusco;
    private Proveedor proveedorLimaLatam;
    private Proveedor proveedorLimaAvianca;
    private Proveedor proveedorCuscoLatam;

    @BeforeEach
    void setUp() {
        usuario = Usuario.builder()
                .nombre("Ana").apellido("Ruiz")
                .correo("ana@saasa.com").documento("12345678")
                .codigoEmpleado("EMP-001").passwordHash("hash")
                .rol(RolEnum.ADMINISTRADOR).estado(1)
                .build();
        em.persist(usuario);

        Estacion lima = Estacion.builder().nombre("Lima").codigoIata("LIM").estado(1).build();
        Estacion cusco = Estacion.builder().nombre("Cusco").codigoIata("CUZ").estado(1).build();
        em.persist(lima);
        em.persist(cusco);

        LineaAerea latam = LineaAerea.builder().codigoIata("LAT").nombre("LATAM").estado(1).build();
        LineaAerea avianca = LineaAerea.builder().codigoIata("AVA").nombre("Avianca").estado(1).build();
        em.persist(latam);
        em.persist(avianca);
        latamId = latam.getId();
        aviancaId = avianca.getId();

        // LATAM habilitada en ambas estaciones; Avianca solo en Lima.
        em.persist(EstacionLineaAerea.builder().estacion(lima).lineaAerea(latam).estado(1).build());
        em.persist(EstacionLineaAerea.builder().estacion(cusco).lineaAerea(latam).estado(1).build());
        em.persist(EstacionLineaAerea.builder().estacion(lima).lineaAerea(avianca).estado(1).build());

        vueloLima = Vuelo.builder()
                .aerolinea("LATAM").codigoVuelo("LA100")
                .origen("LIM").destino("CUZ").fechaVuelo(LocalDate.of(2026, 8, 20))
                .tipoContingencia(ContingenciaEnum.CANCELACION)
                .estado(EstadoVueloEnum.ACTIVO).creadoPor(usuario).build();
        vueloLima.setEstacionId(ESTACION_LIMA);
        vueloLima.setLineaAereaId(latamId);
        em.persist(vueloLima);

        vueloCusco = Vuelo.builder()
                .aerolinea("LATAM").codigoVuelo("LA200")
                .origen("CUZ").destino("LIM").fechaVuelo(LocalDate.of(2026, 8, 20))
                .tipoContingencia(ContingenciaEnum.CANCELACION)
                .estado(EstadoVueloEnum.ACTIVO).creadoPor(usuario).build();
        vueloCusco.setEstacionId(ESTACION_CUSCO);
        vueloCusco.setLineaAereaId(latamId);
        em.persist(vueloCusco);

        proveedorLimaLatam = crearProveedor("Taxi Lima LATAM", "20111111111", ESTACION_LIMA, latamId);
        proveedorLimaAvianca = crearProveedor("Taxi Lima Avianca", "20222222222", ESTACION_LIMA, aviancaId);
        proveedorCuscoLatam = crearProveedor("Taxi Cusco LATAM", "20333333333", ESTACION_CUSCO, latamId);

        em.flush();
    }

    private Proveedor crearProveedor(String nombre, String ruc, Long estacionId, Long lineaAereaId) {
        Proveedor p = Proveedor.builder()
                .tipo(TipoProveedorEnum.TRANSPORTE).nombre(nombre).ruc(ruc).estado(1).build();
        p.setEstacionId(estacionId);
        p.setLineaAereaId(lineaAereaId);
        em.persist(p);
        return p;
    }

    // ══════════════════════════════════════════════════════════════════
    // Vuelo — EstacionSpecifications.porEstacionesDelUsuario
    // ══════════════════════════════════════════════════════════════════

    @Test
    void vuelo_usuarioDeUnaSolaEstacion_soloVeSuEstacion() {
        Specification<Vuelo> filtro = EstacionSpecifications.porEstacionesDelUsuario(List.of(ESTACION_LIMA));

        var pagina = vueloRepository.findAll(filtro, PageRequest.of(0, 10));

        assertEquals(1, pagina.getTotalElements());
        assertEquals(vueloLima.getId(), pagina.getContent().get(0).getId());
    }

    @Test
    void vuelo_usuarioConDosEstaciones_veAmbasPeroNoUnaHipoteticaTercera() {
        Specification<Vuelo> filtro = EstacionSpecifications.porEstacionesDelUsuario(
                List.of(ESTACION_LIMA, ESTACION_CUSCO));

        var pagina = vueloRepository.findAll(filtro, PageRequest.of(0, 10));

        assertEquals(2, pagina.getTotalElements(),
                "Un usuario con acceso a Lima y Cusco debe ver los vuelos de ambas");
    }

    @Test
    void vuelo_usuarioConDosEstaciones_noVeUnaTerceraEstacionFueraDeSuAlcance() {
        // Simula una tercera estación (Trujillo=3L) a la que el usuario NO tiene acceso.
        Vuelo vueloTrujillo = Vuelo.builder()
                .aerolinea("LATAM").codigoVuelo("LA300")
                .origen("TRU").destino("LIM").fechaVuelo(LocalDate.of(2026, 8, 20))
                .tipoContingencia(ContingenciaEnum.CANCELACION)
                .estado(EstadoVueloEnum.ACTIVO).creadoPor(usuario).build();
        vueloTrujillo.setEstacionId(3L);
        vueloTrujillo.setLineaAereaId(latamId);
        em.persist(vueloTrujillo);
        em.flush();

        Specification<Vuelo> filtro = EstacionSpecifications.porEstacionesDelUsuario(
                List.of(ESTACION_LIMA, ESTACION_CUSCO));
        var pagina = vueloRepository.findAll(filtro, PageRequest.of(0, 10));

        assertEquals(2, pagina.getTotalElements());
        assertTrue(pagina.getContent().stream().noneMatch(v -> v.getId().equals(vueloTrujillo.getId())),
                "El vuelo de Trujillo no debe aparecer para un usuario sin acceso a esa estación");
    }

    @Test
    void vuelo_administradorGlobal_veTodasLasEstaciones() {
        // Lista vacía = Administrador Global (RN-804) → EstacionSpecifications retorna null,
        // que Specification.where(null) ignora, sin restricción alguna.
        Specification<Vuelo> filtro = EstacionSpecifications.porEstacionesDelUsuario(List.of());

        var pagina = vueloRepository.findAll(filtro, PageRequest.of(0, 10));

        assertEquals(2, pagina.getTotalElements());
    }

    // ══════════════════════════════════════════════════════════════════
    // Proveedor — EstacionSpecifications + ProveedorSpecification (RN-802)
    // combinados, tal como lo hace ProveedorServiceImpl.findAll()
    // ══════════════════════════════════════════════════════════════════

    @Test
    void proveedor_usuarioDeLima_soloVeProveedoresDeLima() {
        Specification<Proveedor> filtro = EstacionSpecifications.porEstacionesDelUsuario(List.of(ESTACION_LIMA));

        var pagina = proveedorRepository.findAll(filtro, PageRequest.of(0, 10));

        assertEquals(2, pagina.getTotalElements(), "Lima tiene 2 proveedores: LATAM y Avianca");
        assertTrue(pagina.getContent().stream()
                .allMatch(p -> p.getEstacionId().equals(ESTACION_LIMA)));
    }

    @Test
    void proveedor_filtroDeLineaAereaYEstacionSeCombinanSinFugas() {
        // LATAM está habilitada en Lima y en Cusco, y hay un proveedor LATAM en
        // cada una. Un usuario restringido a Lima, aunque filtre por LATAM
        // (que sí opera en ambas estaciones), NO debe ver el proveedor LATAM de Cusco.
        Specification<Proveedor> porLinea = ProveedorSpecification.build(null, null, latamId);
        Specification<Proveedor> filtroEstacion = EstacionSpecifications.porEstacionesDelUsuario(
                List.of(ESTACION_LIMA));
        Specification<Proveedor> combinado = porLinea.and(filtroEstacion);

        var pagina = proveedorRepository.findAll(combinado, PageRequest.of(0, 10));

        assertEquals(1, pagina.getTotalElements());
        assertEquals(proveedorLimaLatam.getId(), pagina.getContent().get(0).getId());
        assertTrue(pagina.getContent().stream().noneMatch(p -> p.getId().equals(proveedorCuscoLatam.getId())),
                "El filtro de línea aérea no debe filtrar sin también respetar el de estación");
    }

    @Test
    void proveedor_administradorGlobal_veProveedoresDeTodasLasEstaciones() {
        Specification<Proveedor> filtro = EstacionSpecifications.porEstacionesDelUsuario(List.of());

        var pagina = proveedorRepository.findAll(filtro, PageRequest.of(0, 10));

        assertEquals(3, pagina.getTotalElements());
    }
}
