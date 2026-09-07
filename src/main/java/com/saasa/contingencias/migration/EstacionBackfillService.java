package com.saasa.contingencias.migration;

import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class EstacionBackfillService {

    private static final Logger log = LoggerFactory.getLogger(EstacionBackfillService.class);

    private static final String CODIGO_IATA_ESTACION_DEFAULT = "LIM";
    private static final String NOMBRE_ESTACION_DEFAULT = "Lima";

    private final EstacionRepository estacionRepository;
    private final LineaAereaRepository lineaAereaRepository;
    private final EstacionLineaAereaRepository estacionLineaAereaRepository;
    private final VueloRepository vueloRepository;
    private final ProveedorRepository proveedorRepository;
    private final RegistroVueloDiarioRepository registroVueloDiarioRepository;
    private final AtencionRepository atencionRepository;
    private final ServicioProveedorRepository servicioProveedorRepository;
    private final VueloRecursoRepository vueloRecursoRepository;
    private final ServicioAsignadoRepository servicioAsignadoRepository;
    private final EnvioPdfRepository envioPdfRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;
    private final UsuarioEstacionRepository usuarioEstacionRepository;

    public EstacionBackfillService(EstacionRepository estacionRepository,
                                   LineaAereaRepository lineaAereaRepository,
                                   EstacionLineaAereaRepository estacionLineaAereaRepository,
                                   VueloRepository vueloRepository,
                                   ProveedorRepository proveedorRepository,
                                   RegistroVueloDiarioRepository registroVueloDiarioRepository,
                                   AtencionRepository atencionRepository,
                                   ServicioProveedorRepository servicioProveedorRepository,
                                   VueloRecursoRepository vueloRecursoRepository,
                                   ServicioAsignadoRepository servicioAsignadoRepository,
                                   EnvioPdfRepository envioPdfRepository,
                                   AuditoriaRepository auditoriaRepository,
                                   UsuarioRepository usuarioRepository,
                                   UsuarioEstacionRepository usuarioEstacionRepository) {
        this.estacionRepository = estacionRepository;
        this.lineaAereaRepository = lineaAereaRepository;
        this.estacionLineaAereaRepository = estacionLineaAereaRepository;
        this.vueloRepository = vueloRepository;
        this.proveedorRepository = proveedorRepository;
        this.registroVueloDiarioRepository = registroVueloDiarioRepository;
        this.atencionRepository = atencionRepository;
        this.servicioProveedorRepository = servicioProveedorRepository;
        this.vueloRecursoRepository = vueloRecursoRepository;
        this.servicioAsignadoRepository = servicioAsignadoRepository;
        this.envioPdfRepository = envioPdfRepository;
        this.auditoriaRepository = auditoriaRepository;
        this.usuarioRepository = usuarioRepository;
        this.usuarioEstacionRepository = usuarioEstacionRepository;
    }

    @Transactional
    public EstacionBackfillResultado ejecutar() {
        Estacion lima = obtenerOCrearEstacionLima();

        Map<String, LineaAerea> catalogoPorNombreNormalizado = new LinkedHashMap<>();
        int lineasCreadas = poblarCatalogoLineasDesdeVuelos(lima, catalogoPorNombreNormalizado);
        LineaAerea lineaPorDefecto = elegirLineaPorDefecto(catalogoPorNombreNormalizado);

        int vuelosActualizados = backfillVuelos(lima, catalogoPorNombreNormalizado, lineaPorDefecto);
        int proveedoresActualizados = backfillProveedores(lima, lineaPorDefecto);
        int registrosActualizados = backfillRegistrosVueloDiario();
        int atencionesActualizadas = backfillAtenciones();
        int serviciosProveedorActualizados = backfillServiciosProveedor();
        int vueloRecursosActualizados = backfillVueloRecursos();
        int serviciosAsignadosActualizados = backfillServiciosAsignados();
        int enviosPdfActualizados = backfillEnviosPdf();
        int auditoriaActualizada = backfillAuditoria(lima);
        int usuariosAsignados = backfillUsuarios(lima);

        EstacionBackfillResultado resultado = new EstacionBackfillResultado(
                true, null, lima.getId(), lineasCreadas,
                vuelosActualizados, proveedoresActualizados, registrosActualizados,
                atencionesActualizadas, serviciosProveedorActualizados, vueloRecursosActualizados,
                serviciosAsignadosActualizados, enviosPdfActualizados, auditoriaActualizada,
                usuariosAsignados
        );

        log.info("Backfill Fase 1/2 completado. Estación Lima id={}, líneas aéreas creadas={}, " +
                        "registros operativos actualizados={}, auditoría actualizada={}, usuarios asignados={}",
                lima.getId(), lineasCreadas, resultado.totalRegistrosOperativosActualizados(),
                auditoriaActualizada, usuariosAsignados);

        return resultado;
    }

    private Estacion obtenerOCrearEstacionLima() {
        return estacionRepository.findByCodigoIata(CODIGO_IATA_ESTACION_DEFAULT)
                .orElseGet(() -> {
                    Estacion nueva = Estacion.builder()
                            .codigoIata(CODIGO_IATA_ESTACION_DEFAULT)
                            .nombre(NOMBRE_ESTACION_DEFAULT)
                            .zonaHoraria("America/Lima")
                            .estado(1)
                            .build();
                    Estacion guardada = estacionRepository.save(nueva);
                    log.info("Estación por defecto creada: {} ({})", guardada.getNombre(), guardada.getCodigoIata());
                    return guardada;
                });
    }

    private int poblarCatalogoLineasDesdeVuelos(Estacion estacion, Map<String, LineaAerea> catalogoOut) {
        for (LineaAerea existente : lineaAereaRepository.findAll()) {
            catalogoOut.put(normalizar(existente.getNombre()), existente);
        }

        Set<String> codigosUsados = new HashSet<>();
        lineaAereaRepository.findAll().forEach(l -> codigosUsados.add(l.getCodigoIata()));

        int creadas = 0;
        for (Vuelo vuelo : vueloRepository.findAll()) {
            String aerolinea = vuelo.getAerolinea();
            if (aerolinea == null || aerolinea.isBlank()) {
                continue;
            }
            String clave = normalizar(aerolinea);
            if (catalogoOut.containsKey(clave)) {
                continue;
            }
            String nombreLimpio = aerolinea.trim();
            String codigo = generarCodigoIata(nombreLimpio, codigosUsados);
            codigosUsados.add(codigo);

            LineaAerea nueva = LineaAerea.builder()
                    .codigoIata(codigo)
                    .nombre(nombreLimpio)
                    .estado(1)
                    .build();
            nueva = lineaAereaRepository.save(nueva);
            catalogoOut.put(clave, nueva);
            creadas++;
            log.info("Línea aérea detectada en histórico y creada: {} ({})", nueva.getNombre(), nueva.getCodigoIata());

            vincularEstacionLinea(estacion, nueva);
        }

        for (LineaAerea l : catalogoOut.values()) {
            vincularEstacionLinea(estacion, l);
        }

        return creadas;
    }

    private void vincularEstacionLinea(Estacion estacion, LineaAerea lineaAerea) {
        estacionLineaAereaRepository.findByEstacionIdAndLineaAereaId(estacion.getId(), lineaAerea.getId())
                .orElseGet(() -> estacionLineaAereaRepository.save(
                        EstacionLineaAerea.builder()
                                .estacion(estacion)
                                .lineaAerea(lineaAerea)
                                .estado(1)
                                .build()
                ));
    }

    private LineaAerea elegirLineaPorDefecto(Map<String, LineaAerea> catalogo) {
        if (catalogo.isEmpty()) {
            log.warn("No se detectó ninguna línea aérea en el histórico de vuelos; " +
                    "Proveedor y los vuelos sin aerolinea quedarán sin linea_aerea_id hasta asignación manual.");
            return null;
        }
        Map<Long, Integer> conteoPorLinea = new HashMap<>();
        for (Vuelo vuelo : vueloRepository.findAll()) {
            LineaAerea l = catalogo.get(normalizar(vuelo.getAerolinea()));
            if (l != null) {
                conteoPorLinea.merge(l.getId(), 1, Integer::sum);
            }
        }
        LineaAerea porDefecto = catalogo.values().iterator().next();
        int maxConteo = -1;
        for (LineaAerea l : catalogo.values()) {
            int conteo = conteoPorLinea.getOrDefault(l.getId(), 0);
            if (conteo > maxConteo) {
                maxConteo = conteo;
                porDefecto = l;
            }
        }
        if (catalogo.size() > 1) {
            log.warn("El histórico tiene {} líneas aéreas distintas. Proveedor no se puede derivar de un " +
                            "Vuelo, así que se le asigna la línea por defecto ({}) — RECOMENDADO: revisar y " +
                            "reasignar manualmente vía el módulo de administración una vez disponible (Fase 2/5).",
                    catalogo.size(), porDefecto.getNombre());
        }
        return porDefecto;
    }

    private int backfillVuelos(Estacion estacion, Map<String, LineaAerea> catalogo, LineaAerea porDefecto) {
        List<Vuelo> pendientes = vueloRepository.findAll().stream()
                .filter(v -> v.getEstacionId() == null)
                .toList();
        for (Vuelo vuelo : pendientes) {
            vuelo.setEstacionId(estacion.getId());
            LineaAerea linea = catalogo.get(normalizar(vuelo.getAerolinea()));
            vuelo.setLineaAereaId(linea != null ? linea.getId() : (porDefecto != null ? porDefecto.getId() : null));
        }
        vueloRepository.saveAll(pendientes);
        return pendientes.size();
    }

    private int backfillProveedores(Estacion estacion, LineaAerea porDefecto) {
        List<Proveedor> pendientes = proveedorRepository.findAll().stream()
                .filter(p -> p.getEstacionId() == null)
                .toList();
        for (Proveedor proveedor : pendientes) {
            proveedor.setEstacionId(estacion.getId());
            proveedor.setLineaAereaId(porDefecto != null ? porDefecto.getId() : null);
        }
        proveedorRepository.saveAll(pendientes);
        return pendientes.size();
    }

    private int backfillRegistrosVueloDiario() {
        List<RegistroVueloDiario> pendientes = registroVueloDiarioRepository.findAll().stream()
                .filter(r -> r.getEstacionId() == null)
                .toList();
        for (RegistroVueloDiario registro : pendientes) {
            Vuelo vuelo = registro.getVueloItinerario();
            if (vuelo != null) {
                registro.setEstacionId(vuelo.getEstacionId());
                registro.setLineaAereaId(vuelo.getLineaAereaId());
            }
        }
        registroVueloDiarioRepository.saveAll(pendientes);
        return pendientes.size();
    }

    private int backfillAtenciones() {
        List<Atencion> pendientes = atencionRepository.findAll().stream()
                .filter(a -> a.getEstacionId() == null)
                .toList();
        for (Atencion atencion : pendientes) {
            Vuelo vuelo = atencion.getVuelo();
            if (vuelo != null) {
                atencion.setEstacionId(vuelo.getEstacionId());
                atencion.setLineaAereaId(vuelo.getLineaAereaId());
            }
        }
        atencionRepository.saveAll(pendientes);
        return pendientes.size();
    }

    private int backfillServiciosProveedor() {
        List<ServicioProveedor> pendientes = servicioProveedorRepository.findAll().stream()
                .filter(s -> s.getEstacionId() == null)
                .toList();
        for (ServicioProveedor servicio : pendientes) {
            Proveedor proveedor = servicio.getProveedor();
            if (proveedor != null) {
                servicio.setEstacionId(proveedor.getEstacionId());
                servicio.setLineaAereaId(proveedor.getLineaAereaId());
            }
        }
        servicioProveedorRepository.saveAll(pendientes);
        return pendientes.size();
    }

    private int backfillVueloRecursos() {
        List<VueloRecurso> pendientes = vueloRecursoRepository.findAll().stream()
                .filter(vr -> vr.getEstacionId() == null)
                .toList();
        for (VueloRecurso recurso : pendientes) {
            Vuelo vuelo = recurso.getVuelo();
            if (vuelo != null) {
                recurso.setEstacionId(vuelo.getEstacionId());
                recurso.setLineaAereaId(vuelo.getLineaAereaId());
            }
        }
        vueloRecursoRepository.saveAll(pendientes);
        return pendientes.size();
    }

    private int backfillServiciosAsignados() {
        List<ServicioAsignado> pendientes = servicioAsignadoRepository.findAll().stream()
                .filter(s -> s.getEstacionId() == null)
                .toList();
        for (ServicioAsignado servicio : pendientes) {
            Atencion atencion = servicio.getAtencion();
            if (atencion != null) {
                servicio.setEstacionId(atencion.getEstacionId());
                servicio.setLineaAereaId(atencion.getLineaAereaId());
            }
        }
        servicioAsignadoRepository.saveAll(pendientes);
        return pendientes.size();
    }

    private int backfillEnviosPdf() {
        List<EnvioPdf> pendientes = envioPdfRepository.findAll().stream()
                .filter(e -> e.getEstacionId() == null)
                .toList();
        for (EnvioPdf envio : pendientes) {
            Atencion atencion = envio.getAtencion();
            if (atencion != null) {
                envio.setEstacionId(atencion.getEstacionId());
                envio.setLineaAereaId(atencion.getLineaAereaId());
            }
        }
        envioPdfRepository.saveAll(pendientes);
        return pendientes.size();
    }

    private int backfillAuditoria(Estacion estacion) {
        List<Auditoria> pendientes = auditoriaRepository.findAll().stream()
                .filter(a -> a.getEstacionId() == null)
                .toList();
        for (Auditoria auditoria : pendientes) {
            auditoria.setEstacionId(estacion.getId());
        }
        auditoriaRepository.saveAll(pendientes);
        return pendientes.size();
    }

    /**
     * Fase 2: asigna a la estación por defecto (Lima) a TODO usuario existente
     * que aún no tenga ninguna fila en usuario_estacion — sin excluir a los
     * ADMINISTRADOR actuales. Es deliberado: la sección 9.3 define
     * "Administrador Global" como "usuario sin registros en usuario_estacion",
     * así que dejar a cualquier usuario histórico sin fila lo convertiría
     * accidentalmente en Administrador Global (acceso sin restricción) en vez
     * de mantenerlo igual de acotado que estaba antes de la migración. Quien
     * deba ser Administrador Global de verdad se define explícitamente después,
     * quitándole la asignación desde el módulo de administración (Fase 2/5).
     *
     * Idempotente: solo toca usuarios sin ninguna fila en usuario_estacion, así
     * que no reasigna ni duplica asignaciones ya hechas manualmente.
     */
    private int backfillUsuarios(Estacion estacion) {
        List<Usuario> pendientes = usuarioRepository.findAll().stream()
                .filter(u -> usuarioEstacionRepository.findByUsuarioId(u.getId()).isEmpty())
                .toList();
        List<UsuarioEstacion> nuevas = pendientes.stream()
                .map(u -> UsuarioEstacion.builder().usuario(u).estacion(estacion).estado(1).build())
                .toList();
        usuarioEstacionRepository.saveAll(nuevas);
        return nuevas.size();
    }

    private static String normalizar(String texto) {
        return texto == null ? "" : texto.trim().toUpperCase(Locale.ROOT);
    }

    private static String generarCodigoIata(String nombre, Set<String> usados) {
        String letras = nombre.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        if (letras.isEmpty()) {
            letras = "XXX";
        }
        String base = letras.length() >= 3 ? letras.substring(0, 3) : (letras + "XXX").substring(0, 3);
        if (!usados.contains(base)) {
            return base;
        }
        for (int i = 1; i <= 9; i++) {
            String candidato = base.substring(0, 2) + i;
            if (!usados.contains(candidato)) {
                return candidato;
            }
        }
        for (int i = 0; i <= 99; i++) {
            String candidato = String.format("%c%02d", base.charAt(0), i);
            if (!usados.contains(candidato)) {
                return candidato;
            }
        }
        throw new IllegalStateException("No se pudo generar un código IATA único de 3 caracteres para: " + nombre);
    }
}
