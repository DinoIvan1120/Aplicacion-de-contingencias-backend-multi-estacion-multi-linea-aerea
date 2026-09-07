package com.saasa.contingencias.domain.repository;

import com.saasa.contingencias.domain.enumeration.*;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.util.DateTimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
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
 * Verifica el filtro por estación+línea aérea (extensión multi-estación /
 * multi-línea aérea, ver Documento Funcional Multi-Estación, sección 3
 * "estación + línea aérea como par de aislamiento") en las 6 consultas SQL
 * nativas de {@link AtencionRepository}. Este filtro no se puede probar con
 * mocks porque vive en SQL crudo (no en una Specification de JPA), por lo
 * que necesita una base de datos real para validar que el fragmento
 * {@code AND estacion_id = :estacionId AND linea_aerea_id = :lineaAereaId}
 * realmente excluye/incluye filas.
 *
 * A diferencia de la versión anterior del filtro (un flag "restringir" con
 * una lista de estaciones, donde Administrador Global pasaba con
 * restringir=0 y una lista placeholder), ahora las 6 consultas SIEMPRE
 * filtran por un ÚNICO par estación+línea aérea exacto: el "contexto de
 * trabajo activo" que {@code EstacionContext#resolverContextoActivo()}
 * resuelve del selector del topbar, obligatorio también para Administrador
 * Global (ver ReporteServiceImpl.contextoActivoParaReportes()). Por eso ya
 * no existe un escenario "Administrador Global ve todas las estaciones" a
 * nivel de este repositorio — eso se decide antes, al resolver el contexto.
 *
 * Escenario de datos: dos pares estación+línea aérea (Lima+PlusUltra y
 * Cusco+Iberia), cada uno con una atención ACTIVO y su propio servicio
 * asignado (para las consultas de distribución), más una tercera atención
 * ANULADA en Lima+PlusUltra para confirmar que el filtro de estado sigue
 * aplicando independientemente del filtro de estación/línea.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AtencionRepositoryEstacionFiltroTest {

    private static final Long ESTACION_LIMA = 1L;
    private static final Long ESTACION_CUSCO = 2L;

    private static final Long LINEA_PLUS_ULTRA = 10L;
    private static final Long LINEA_IBERIA = 20L;

    @Autowired private AtencionRepository atencionRepository;
    @Autowired private jakarta.persistence.EntityManager em;

    private Usuario usuario;
    private Vuelo vuelo;
    private Proveedor proveedorLima;
    private Proveedor proveedorCusco;
    private VueloRecurso recursoLima;
    private VueloRecurso recursoCusco;

    private LocalDateTime inicio;
    private LocalDateTime fin;

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

        proveedorLima = Proveedor.builder()
                .tipo(TipoProveedorEnum.TRANSPORTE)
                .nombre("Taxi Lima").ruc("20111111111")
                .estado(1)
                .build();
        proveedorLima.setEstacionId(ESTACION_LIMA);
        em.persist(proveedorLima);

        proveedorCusco = Proveedor.builder()
                .tipo(TipoProveedorEnum.HOTEL)
                .nombre("Hotel Cusco").ruc("20222222222")
                .estado(1)
                .build();
        proveedorCusco.setEstacionId(ESTACION_CUSCO);
        em.persist(proveedorCusco);

        recursoLima = VueloRecurso.builder()
                .vuelo(vuelo).proveedor(proveedorLima)
                .capacidadTotal(10)
                .habilitadoPor(usuario).habilitadoEn(LocalDateTime.now())
                .estado(1)
                .build();
        recursoLima.setEstacionId(ESTACION_LIMA);
        em.persist(recursoLima);

        recursoCusco = VueloRecurso.builder()
                .vuelo(vuelo).proveedor(proveedorCusco)
                .capacidadTotal(5)
                .habilitadoPor(usuario).habilitadoEn(LocalDateTime.now())
                .estado(1)
                .build();
        recursoCusco.setEstacionId(ESTACION_CUSCO);
        em.persist(recursoCusco);

        // Rango amplio alrededor de "ahora" — createdAt se estampa solo via @PrePersist
        // (AuditableEntity), así que no se puede fijar una fecha histórica arbitraria.
        LocalDateTime ahora = DateTimeUtil.ahoraEnLima();
        inicio = ahora.minusMinutes(10);
        fin = ahora.plusMinutes(10);
    }

    /** Crea y persiste una Atencion con estacionId, lineaAereaId y monto dados. */
    private Atencion crearAtencion(String correlativo, Long estacionId, Long lineaAereaId,
                                   EstadoAtencionEnum estado, BigDecimal monto) {
        Atencion a = Atencion.builder()
                .numeroCorrelativo(correlativo)
                .vuelo(vuelo)
                .nombre("Juan").apellido("Perez")
                .pnr("ABC123").correo("jp@test.com")
                .montoTotal(monto)
                .estado(estado)
                .atendidoPor(usuario)
                .build();
        a.setEstacionId(estacionId);
        a.setLineaAereaId(lineaAereaId);
        em.persist(a);
        return a;
    }

    /** Asigna un servicio a la atención, usado por las consultas de distribución. */
    private void asignarServicio(Atencion atencion, VueloRecurso recurso,
                                 TipoDetalleEnum tipo, BigDecimal monto) {
        ServicioAsignado sa = ServicioAsignado.builder()
                .atencion(atencion)
                .vueloRecurso(recurso)
                .tipoDetalle(tipo)
                .cantidad(1)
                .montoUnitario(monto)
                .montoSubtotal(monto)
                .asignadoEn(LocalDateTime.now())
                .build();
        em.persist(sa);
    }

    // ══════════════════════════════════════════════════════════════════
    // Escenario común: Lima+PlusUltra (activa $100) + Cusco+Iberia (activa $200)
    // + Lima+PlusUltra anulada ($999, siempre debe quedar excluida)
    // ══════════════════════════════════════════════════════════════════
    private Atencion prepararEscenarioBasico() {
        Atencion limaActiva = crearAtencion("SGC-000000001", ESTACION_LIMA, LINEA_PLUS_ULTRA,
                EstadoAtencionEnum.ACTIVO, BigDecimal.valueOf(100));
        crearAtencion("SGC-000000002", ESTACION_LIMA, LINEA_PLUS_ULTRA,
                EstadoAtencionEnum.ANULADO, BigDecimal.valueOf(999));
        Atencion cuscoActiva = crearAtencion("SGC-000000003", ESTACION_CUSCO, LINEA_IBERIA,
                EstadoAtencionEnum.ACTIVO, BigDecimal.valueOf(200));

        asignarServicio(limaActiva, recursoLima, TipoDetalleEnum.TRANSPORTE, BigDecimal.valueOf(100));
        asignarServicio(cuscoActiva, recursoCusco, TipoDetalleEnum.HOTEL, BigDecimal.valueOf(200));

        em.flush();
        return limaActiva;
    }

    // ══════════════════════════════════════════════════════════════════
    // 1. countActivosByFechaRange
    // ══════════════════════════════════════════════════════════════════

    @Test
    void countActivosByFechaRange_limaYPlusUltra_soloVeLima() {
        prepararEscenarioBasico();

        Long total = atencionRepository.countActivosByFechaRange(inicio, fin, ESTACION_LIMA, LINEA_PLUS_ULTRA);

        assertEquals(1, total, "El par Lima+PlusUltra solo debe contar la activa de Lima, sin la anulada");
    }

    @Test
    void countActivosByFechaRange_cuscoYIberia_soloVeCusco() {
        prepararEscenarioBasico();

        Long total = atencionRepository.countActivosByFechaRange(inicio, fin, ESTACION_CUSCO, LINEA_IBERIA);

        assertEquals(1, total, "El par Cusco+Iberia solo debe contar la activa de Cusco");
    }

    @Test
    void countActivosByFechaRange_estacionCoincideLineaNo_noVeNada() {
        prepararEscenarioBasico();

        // Lima existe, pero con Iberia (que en este escenario solo opera en Cusco):
        // la línea aérea también es eje de aislamiento, no solo la estación.
        Long total = atencionRepository.countActivosByFechaRange(inicio, fin, ESTACION_LIMA, LINEA_IBERIA);

        assertEquals(0, total, "Aunque la estación coincida, una línea aérea distinta no debe ver datos");
    }

    @Test
    void countActivosByFechaRange_parSinDatos_noVeNada() {
        prepararEscenarioBasico();

        Long total = atencionRepository.countActivosByFechaRange(inicio, fin, 999L, 999L);

        assertEquals(0, total, "Un par estación+línea sin datos no debe filtrar por accidente otros pares");
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. sumMontoByFechaRange
    // ══════════════════════════════════════════════════════════════════

    @Test
    void sumMontoByFechaRange_limaYPlusUltra_soloSumaLima() {
        prepararEscenarioBasico();

        BigDecimal suma = atencionRepository.sumMontoByFechaRange(inicio, fin, ESTACION_LIMA, LINEA_PLUS_ULTRA);

        assertEquals(0, BigDecimal.valueOf(100).compareTo(suma),
                "Debe sumar solo los 100 de Lima+PlusUltra, sin la anulada de 999 ni los 200 de Cusco");
    }

    @Test
    void sumMontoByFechaRange_cuscoYIberia_soloSumaCusco() {
        prepararEscenarioBasico();

        BigDecimal suma = atencionRepository.sumMontoByFechaRange(inicio, fin, ESTACION_CUSCO, LINEA_IBERIA);

        assertEquals(0, BigDecimal.valueOf(200).compareTo(suma));
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. countByFecha (agrupado por día)
    // ══════════════════════════════════════════════════════════════════

    @Test
    void countByFecha_limaYPlusUltra_soloCuentaLima() {
        prepararEscenarioBasico();

        List<Object[]> filas = atencionRepository.countByFecha(inicio, fin, ESTACION_LIMA, LINEA_PLUS_ULTRA);

        long totalDia = filas.stream().mapToLong(f -> ((Number) f[1]).longValue()).sum();
        assertEquals(1, totalDia, "Filtrado a Lima+PlusUltra, el total del día debe ser solo el de Lima");
    }

    @Test
    void countByFecha_cuscoYIberia_soloCuentaCusco() {
        prepararEscenarioBasico();

        List<Object[]> filas = atencionRepository.countByFecha(inicio, fin, ESTACION_CUSCO, LINEA_IBERIA);

        long totalDia = filas.stream().mapToLong(f -> ((Number) f[1]).longValue()).sum();
        assertEquals(1, totalDia, "Filtrado a Cusco+Iberia, el total del día debe ser solo el de Cusco");
    }

    // ══════════════════════════════════════════════════════════════════
    // 4. importeByFecha (agrupado por día)
    // ══════════════════════════════════════════════════════════════════

    @Test
    void importeByFecha_limaYPlusUltra_soloImportaLima() {
        prepararEscenarioBasico();

        List<Object[]> filas = atencionRepository.importeByFecha(inicio, fin, ESTACION_LIMA, LINEA_PLUS_ULTRA);

        BigDecimal total = filas.stream()
                .map(f -> (BigDecimal) f[1])
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, BigDecimal.valueOf(100).compareTo(total));
    }

    @Test
    void importeByFecha_cuscoYIberia_soloImportaCusco() {
        prepararEscenarioBasico();

        List<Object[]> filas = atencionRepository.importeByFecha(inicio, fin, ESTACION_CUSCO, LINEA_IBERIA);

        BigDecimal total = filas.stream()
                .map(f -> (BigDecimal) f[1])
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, BigDecimal.valueOf(200).compareTo(total));
    }

    // ══════════════════════════════════════════════════════════════════
    // 5. distribucionByTipo
    // ══════════════════════════════════════════════════════════════════

    @Test
    void distribucionByTipo_limaYPlusUltra_soloVeTransporte() {
        prepararEscenarioBasico();

        List<Object[]> filas = atencionRepository.distribucionByTipo(inicio, fin, ESTACION_LIMA, LINEA_PLUS_ULTRA);

        assertEquals(1, filas.size());
        assertEquals("TRANSPORTE", filas.get(0)[0]);
        assertEquals(0, BigDecimal.valueOf(100).compareTo((BigDecimal) filas.get(0)[2]));
    }

    @Test
    void distribucionByTipo_cuscoYIberia_soloVeHotel() {
        prepararEscenarioBasico();

        List<Object[]> filas = atencionRepository.distribucionByTipo(inicio, fin, ESTACION_CUSCO, LINEA_IBERIA);

        assertEquals(1, filas.size());
        assertEquals("HOTEL", filas.get(0)[0]);
        assertEquals(0, BigDecimal.valueOf(200).compareTo((BigDecimal) filas.get(0)[2]));
    }

    // ══════════════════════════════════════════════════════════════════
    // 6. distribucionByProveedor
    // ══════════════════════════════════════════════════════════════════

    @Test
    void distribucionByProveedor_limaYPlusUltra_soloVeSuProveedor() {
        prepararEscenarioBasico();

        List<Object[]> filas = atencionRepository.distribucionByProveedor(inicio, fin, ESTACION_LIMA, LINEA_PLUS_ULTRA);

        assertEquals(1, filas.size());
        assertEquals("Taxi Lima", filas.get(0)[1]);
    }

    @Test
    void distribucionByProveedor_cuscoYIberia_soloVeSuProveedor() {
        prepararEscenarioBasico();

        List<Object[]> filas = atencionRepository.distribucionByProveedor(inicio, fin, ESTACION_CUSCO, LINEA_IBERIA);

        assertEquals(1, filas.size());
        assertEquals("Hotel Cusco", filas.get(0)[1]);
    }

    // ══════════════════════════════════════════════════════════════════
    // Caso de seguridad explícito: el filtro nunca se "olvida" de excluir
    // una fila con estacion_id o linea_aerea_id NULL (ventana de migración)
    // ══════════════════════════════════════════════════════════════════

    @Test
    void filtroExacto_noExponeAtencionesConEstacionOLineaAereaNull() {
        // Atenciones "legacy" aún sin backfillear: una sin estación, otra
        // con estación pero sin línea aérea backfilleada todavía.
        crearAtencion("SGC-000000098", null, null, EstadoAtencionEnum.ACTIVO, BigDecimal.valueOf(50));
        crearAtencion("SGC-000000099", ESTACION_LIMA, null, EstadoAtencionEnum.ACTIVO, BigDecimal.valueOf(75));
        crearAtencion("SGC-000000001", ESTACION_LIMA, LINEA_PLUS_ULTRA, EstadoAtencionEnum.ACTIVO, BigDecimal.valueOf(100));
        em.flush();

        Long total = atencionRepository.countActivosByFechaRange(inicio, fin, ESTACION_LIMA, LINEA_PLUS_ULTRA);

        assertEquals(1, total,
                "Un registro con estacion_id o linea_aerea_id NULL no debe colarse en el filtro exacto " +
                        "(la igualdad con NULL en SQL estándar nunca es verdadera)");
    }

    // ══════════════════════════════════════════════════════════════════
    // REGRESIÓN bug historial: lineaAereaId == null en el parámetro de la
    // query significa "todas las líneas de esa estación" (usuario en modo
    // "Continuar sin filtrar por línea"), NO "no traer nada". Antes del fix
    // "AND linea_aerea_id = :lineaAereaId" con :lineaAereaId=null nunca
    // matcheaba ninguna fila en SQL estándar (comparar con NULL es siempre
    // falso), así que el resumen de reportes se veía en cero.
    // ══════════════════════════════════════════════════════════════════

    @Test
    void countActivosByFechaRange_sinLineaAerea_cuentaTodasLasLineasDeLaEstacion() {
        prepararEscenarioBasico();

        Long total = atencionRepository.countActivosByFechaRange(inicio, fin, ESTACION_LIMA, null);

        assertEquals(1, total,
                "lineaAereaId null debe contar todas las líneas de Lima (aquí solo hay PlusUltra activa), " +
                        "pero seguir excluyendo Cusco y la anulada");
    }

    @Test
    void sumMontoByFechaRange_sinLineaAerea_sumaTodasLasLineasDeLaEstacion() {
        prepararEscenarioBasico();

        BigDecimal suma = atencionRepository.sumMontoByFechaRange(inicio, fin, ESTACION_LIMA, null);

        assertEquals(0, BigDecimal.valueOf(100).compareTo(suma),
                "lineaAereaId null no debe filtrar por línea, solo por estación (Lima = 100, sin Cusco ni la anulada)");
    }

    @Test
    void distribucionByProveedor_sinLineaAerea_incluyeTodasLasLineasDeLaEstacion() {
        prepararEscenarioBasico();

        List<Object[]> filas = atencionRepository.distribucionByProveedor(inicio, fin, ESTACION_LIMA, null);

        assertEquals(1, filas.size());
        assertEquals("Taxi Lima", filas.get(0)[1]);
    }
}
