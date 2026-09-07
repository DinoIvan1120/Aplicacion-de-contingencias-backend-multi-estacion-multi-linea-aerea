package com.saasa.contingencias.migration;

import com.saasa.contingencias.domain.enumeration.ContingenciaEnum;
import com.saasa.contingencias.domain.enumeration.EstadoAtencionEnum;
import com.saasa.contingencias.domain.enumeration.EstadoVueloEnum;
import com.saasa.contingencias.domain.enumeration.RolEnum;
import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de la Fase 1: "Pruebas para confirmar que la información existente
 * quedó correctamente actualizada" (backfill de estación 'Lima' y de la
 * línea aérea correspondiente a cada registro), más el backfill de usuarios
 * en `usuario_estacion` que se agrega en la Fase 2.
 *
 * Usa H2 en memoria (@DataJpaTest, mismo mecanismo que el resto de pruebas
 * del proyecto vía src/test/resources/application.properties) para validar
 * el comportamiento real contra las entidades JPA, no un mock del backfill.
 */
@DataJpaTest
@AutoConfigureTestDatabase
@Import(EstacionBackfillService.class)
class EstacionBackfillServiceTest {

    @Autowired EstacionBackfillService backfillService;

    @Autowired EstacionRepository estacionRepository;
    @Autowired LineaAereaRepository lineaAereaRepository;
    @Autowired EstacionLineaAereaRepository estacionLineaAereaRepository;
    @Autowired UsuarioRepository usuarioRepository;
    @Autowired UsuarioEstacionRepository usuarioEstacionRepository;
    @Autowired VueloRepository vueloRepository;
    @Autowired ProveedorRepository proveedorRepository;
    @Autowired RegistroVueloDiarioRepository registroVueloDiarioRepository;
    @Autowired AtencionRepository atencionRepository;
    @Autowired ServicioProveedorRepository servicioProveedorRepository;
    @Autowired VueloRecursoRepository vueloRecursoRepository;
    @Autowired AuditoriaRepository auditoriaRepository;

    private Usuario usuario;
    private Vuelo vueloPlusUltra;
    private Vuelo vueloLatam;

    @BeforeEach
    void setUp() {
        usuario = usuarioRepository.save(Usuario.builder()
                .nombre("Ana").apellido("Torres").correo("ana@saasa.pe")
                .documento("12345678").codigoEmpleado("EMP-001")
                .passwordHash("hash").rol(RolEnum.LIDER_SAASA).estado(1)
                .build());

        vueloPlusUltra = vueloRepository.save(Vuelo.builder()
                .aerolinea("Plus Ultra").codigoVuelo("PU302").origen("LIM").destino("MAD")
                .fechaVuelo(LocalDate.now()).tipoContingencia(ContingenciaEnum.DEMORA)
                .estado(EstadoVueloEnum.ACTIVO)
                .creadoPor(usuario).build());

        vueloLatam = vueloRepository.save(Vuelo.builder()
                .aerolinea("  latam  ").codigoVuelo("LA2501").origen("LIM").destino("CUZ")
                .fechaVuelo(LocalDate.now()).tipoContingencia(ContingenciaEnum.PROGRAMADO)
                .estado(EstadoVueloEnum.ACTIVO)
                .creadoPor(usuario).build());

        auditoriaRepository.save(Auditoria.builder()
                .usuario(usuario).accion("CREAR_VUELO").modulo("VUELOS").build());
    }

    @Test
    void ejecutar_creaEstacionLimaUnaSolaVez() {
        EstacionBackfillResultado r1 = backfillService.ejecutar();
        assertNotNull(r1.estacionLimaId());
        assertEquals(1, estacionRepository.count());
        Estacion lima = estacionRepository.findByCodigoIata("LIM").orElseThrow();
        assertEquals("Lima", lima.getNombre());

        backfillService.ejecutar(); // segunda corrida: idempotente
        assertEquals(1, estacionRepository.count());
    }

    @Test
    void ejecutar_detectaLineasAereasDistintasDesdeAerolineaLibre_ignorandoMayusculasYEspacios() {
        backfillService.ejecutar();

        // "Plus Ultra" y "  latam  " son dos aerolíneas distintas -> 2 registros en el catálogo.
        assertEquals(2, lineaAereaRepository.count());
        assertTrue(lineaAereaRepository.findByNombreIgnoreCase("Plus Ultra").isPresent());
        assertTrue(lineaAereaRepository.findByNombreIgnoreCase("latam").isPresent());

        Estacion lima = estacionRepository.findByCodigoIata("LIM").orElseThrow();
        List<EstacionLineaAerea> vinculos = estacionLineaAereaRepository.findByEstacionId(lima.getId());
        assertEquals(2, vinculos.size(), "Ambas líneas deben quedar vinculadas a la estación Lima");
    }

    @Test
    void ejecutar_backfillDeVuelos_asignaEstacionYLineaCorrecta() {
        backfillService.ejecutar();

        Estacion lima = estacionRepository.findByCodigoIata("LIM").orElseThrow();
        LineaAerea plusUltra = lineaAereaRepository.findByNombreIgnoreCase("Plus Ultra").orElseThrow();
        LineaAerea latam = lineaAereaRepository.findByNombreIgnoreCase("latam").orElseThrow();

        Vuelo puActualizado = vueloRepository.findById(vueloPlusUltra.getId()).orElseThrow();
        Vuelo laActualizado = vueloRepository.findById(vueloLatam.getId()).orElseThrow();

        assertEquals(lima.getId(), puActualizado.getEstacionId());
        assertEquals(plusUltra.getId(), puActualizado.getLineaAereaId());

        assertEquals(lima.getId(), laActualizado.getEstacionId());
        assertEquals(latam.getId(), laActualizado.getLineaAereaId(),
                "El texto libre '  latam  ' debe mapear a la misma LineaAerea que 'latam' normalizado");
    }

    @Test
    void ejecutar_backfillDeEntidadesDependientes_heredaEstacionYLineaDeSuPadre() {
        Proveedor hotel = proveedorRepository.save(Proveedor.builder()
                .tipo(TipoProveedorEnum.HOTEL).nombre("Hotel Sheraton").ruc("20123456789").estado(1).build());

        RegistroVueloDiario registro = registroVueloDiarioRepository.save(RegistroVueloDiario.builder()
                .vueloItinerario(vueloPlusUltra).registradoPor(usuario)
                .fechaRegistro(LocalDate.now()).registradoEn(LocalDateTime.now()).active(true).build());

        Atencion atencion = atencionRepository.save(Atencion.builder()
                .numeroCorrelativo("SGC-000000001").vuelo(vueloPlusUltra).registroVueloDiario(registro)
                .nombre("Juan").apellido("Perez").pnr("ABC123").correo("juan@test.com")
                .montoTotal(BigDecimal.TEN).estado(EstadoAtencionEnum.ACTIVO).atendidoPor(usuario).build());

        ServicioProveedor servicio = servicioProveedorRepository.save(ServicioProveedor.builder()
                .proveedor(hotel).tipoServicio("Habitación").monto(BigDecimal.valueOf(150)).estado(1).build());

        VueloRecurso recurso = vueloRecursoRepository.save(VueloRecurso.builder()
                .vuelo(vueloPlusUltra).proveedor(hotel).habilitadoPor(usuario)
                .habilitadoEn(LocalDateTime.now()).estado(1).build());

        backfillService.ejecutar();

        Estacion lima = estacionRepository.findByCodigoIata("LIM").orElseThrow();
        LineaAerea plusUltra = lineaAereaRepository.findByNombreIgnoreCase("Plus Ultra").orElseThrow();

        // Proveedor no se puede derivar de un Vuelo: recibe la estación y la línea por defecto.
        Proveedor hotelActualizado = proveedorRepository.findById(hotel.getId()).orElseThrow();
        assertEquals(lima.getId(), hotelActualizado.getEstacionId());
        assertNotNull(hotelActualizado.getLineaAereaId());

        RegistroVueloDiario registroActualizado = registroVueloDiarioRepository.findById(registro.getId()).orElseThrow();
        assertEquals(lima.getId(), registroActualizado.getEstacionId());
        assertEquals(plusUltra.getId(), registroActualizado.getLineaAereaId());

        Atencion atencionActualizada = atencionRepository.findById(atencion.getId()).orElseThrow();
        assertEquals(lima.getId(), atencionActualizada.getEstacionId());
        assertEquals(plusUltra.getId(), atencionActualizada.getLineaAereaId());

        ServicioProveedor servicioActualizado = servicioProveedorRepository.findById(servicio.getId()).orElseThrow();
        assertEquals(lima.getId(), servicioActualizado.getEstacionId());
        assertEquals(hotelActualizado.getLineaAereaId(), servicioActualizado.getLineaAereaId());

        VueloRecurso recursoActualizado = vueloRecursoRepository.findById(recurso.getId()).orElseThrow();
        assertEquals(lima.getId(), recursoActualizado.getEstacionId());
        assertEquals(plusUltra.getId(), recursoActualizado.getLineaAereaId(),
                "VueloRecurso debe heredar la línea del Vuelo, no del Proveedor");
    }

    @Test
    void ejecutar_backfillDeAuditoria_soloAsignaEstacionSinLineaAerea() {
        backfillService.ejecutar();

        Estacion lima = estacionRepository.findByCodigoIata("LIM").orElseThrow();
        Auditoria auditoria = auditoriaRepository.findAll().get(0);
        assertEquals(lima.getId(), auditoria.getEstacionId());
    }

    @Test
    void ejecutar_noPisaAsignacionesYaExistentes() {
        backfillService.ejecutar();
        Vuelo puAntes = vueloRepository.findById(vueloPlusUltra.getId()).orElseThrow();
        Long lineaOriginal = puAntes.getLineaAereaId();

        // Simula una reasignación manual posterior (p. ej. desde el módulo de administración).
        puAntes.setLineaAereaId(999L);
        vueloRepository.save(puAntes);

        backfillService.ejecutar(); // no debe tocar filas que ya tienen estacionId asignado

        Vuelo puDespues = vueloRepository.findById(vueloPlusUltra.getId()).orElseThrow();
        assertEquals(999L, puDespues.getLineaAereaId(), "El backfill no debe sobrescribir asignaciones ya hechas");
        assertNotEquals(lineaOriginal, 999L, "Sanity check: el valor de prueba es distinto del original");
    }

    // ─── Fase 2: backfill de usuarios en usuario_estacion ─────────────────────

    @Test
    void ejecutar_asignaTodosLosUsuariosExistentesALima_paraQueNingunoQuedeGlobalPorOmision() {
        EstacionBackfillResultado resultado = backfillService.ejecutar();

        assertEquals(1, resultado.usuariosAsignados());
        Estacion lima = estacionRepository.findByCodigoIata("LIM").orElseThrow();
        List<UsuarioEstacion> asignaciones = usuarioEstacionRepository.findByUsuarioId(usuario.getId());
        assertEquals(1, asignaciones.size());
        assertEquals(lima.getId(), asignaciones.get(0).getEstacion().getId());
        assertEquals(1, asignaciones.get(0).getEstado());
    }

    @Test
    void ejecutar_asignaUsuariosIncluyendoRolAdministrador() {
        Usuario admin = usuarioRepository.save(Usuario.builder()
                .nombre("Root").apellido("Admin").correo("admin@saasa.pe")
                .documento("00000001").codigoEmpleado("ADM-001")
                .passwordHash("hash").rol(RolEnum.ADMINISTRADOR).estado(1)
                .build());

        backfillService.ejecutar();

        // Deliberado: hasta un ADMINISTRADOR existente queda asignado a Lima por el
        // backfill, para no volverse "Administrador Global" por omisión (sección 9.3).
        assertFalse(usuarioEstacionRepository.findByUsuarioId(admin.getId()).isEmpty());
    }

    @Test
    void ejecutar_noDuplicaAsignacionDeUsuarioEnSegundaCorrida() {
        backfillService.ejecutar();
        backfillService.ejecutar();
        assertEquals(1, usuarioEstacionRepository.findByUsuarioId(usuario.getId()).size());
    }

    @Test
    void ejecutar_noReasignaUsuarioQueYaTieneEstacionAsignadaManualmente() {
        // Simula que el módulo de administración ya había asignado a este usuario
        // a otra estación antes de correr el backfill (p. ej. tras crear una segunda estación).
        Estacion cusco = estacionRepository.save(Estacion.builder()
                .codigoIata("CUZ").nombre("Cusco").zonaHoraria("America/Lima").estado(1).build());
        usuarioEstacionRepository.save(UsuarioEstacion.builder()
                .usuario(usuario).estacion(cusco).estado(1).build());

        EstacionBackfillResultado resultado = backfillService.ejecutar();

        assertEquals(0, resultado.usuariosAsignados(), "No debe tocar a un usuario que ya tenía una fila en usuario_estacion");
        List<UsuarioEstacion> asignaciones = usuarioEstacionRepository.findByUsuarioId(usuario.getId());
        assertEquals(1, asignaciones.size());
        assertEquals("CUZ", asignaciones.get(0).getEstacion().getCodigoIata());
    }
}

