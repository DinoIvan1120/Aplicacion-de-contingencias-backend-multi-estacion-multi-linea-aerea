package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.dto.response.CargaMasivaAtencionResponse;
import com.saasa.contingencias.domain.dto.response.CargaMasivaPreviewResponse;
import com.saasa.contingencias.domain.dto.response.DisponibilidadResponse;
import com.saasa.contingencias.domain.dto.response.LoteEstadoResponse;
import com.saasa.contingencias.domain.enumeration.EstadoDetalleLoteEnum;
import com.saasa.contingencias.domain.enumeration.EstadoLoteEnum;
import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import com.saasa.contingencias.domain.enumeration.TipoProveedorEnum;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.service.IAtencionCargaMasivaService;
import com.saasa.contingencias.service.IDisponibilidadService;
import com.saasa.contingencias.util.PnrValidator;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Carga masiva de pasajeros + servicio de RESTAURANTE desde Excel.
 *
 * RESPONSABILIDAD DE ESTA CLASE (síncrona, dentro de una sola transacción):
 *   1. Parsear y validar el Excel fila por fila (sin detener el proceso
 *      ante errores parciales — igual que VueloExcelServiceImpl).
 *   2. Agrupar las filas válidas por PNR (grupo = mismo PNR).
 *   3. Validar que la capacidad disponible del restaurante alcance para
 *      el total de pax solicitado en TODO el archivo (falla rápido, antes
 *      de crear nada, si no alcanza).
 *   4. Crear las Atenciones y asignarles el servicio de RESTAURANTE,
 *      reutilizando IAtencionService (mismas reglas/validaciones que la
 *      carga manual).
 *   5. Dejar un CargaMasivaLote + sus CargaMasivaDetalle en estado
 *      PENDIENTE, listos para que VoucherLoteOrchestratorImpl los procese
 *      en background (ver AtencionCargaMasivaController, que dispara ese
 *      procesamiento DESPUÉS de que esta transacción confirma).
 *
 * Esta clase NUNCA genera ni envía vouchers — solo deja todo listo.
 */
@Service
public class AtencionCargaMasivaServiceImpl implements IAtencionCargaMasivaService {

    private static final Logger log = LoggerFactory.getLogger(AtencionCargaMasivaServiceImpl.class);

    private static final Pattern TELEFONO_PATTERN = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final String[] HEADERS = {
            "Nombres", "Apellidos", "PNR", "Correo electrónico",
            "Celular (opcional)", "Cant. Pax Restaurante",
            "Desayuno (SI/NO)", "Almuerzo (SI/NO)", "Cena (SI/NO)"
    };

    private final IDisponibilidadService disponibilidadService;
    private final RegistroVueloDiarioRepository registroVueloDiarioRepository;
    private final VueloRecursoRepository vueloRecursoRepository;
    private final UsuarioRepository usuarioRepository;
    private final CargaMasivaLoteRepository loteRepository;
    private final CargaMasivaDetalleRepository detalleRepository;

    public AtencionCargaMasivaServiceImpl(IDisponibilidadService disponibilidadService,
                                          RegistroVueloDiarioRepository registroVueloDiarioRepository,
                                          VueloRecursoRepository vueloRecursoRepository,
                                          UsuarioRepository usuarioRepository,
                                          CargaMasivaLoteRepository loteRepository,
                                          CargaMasivaDetalleRepository detalleRepository) {
        this.disponibilidadService = disponibilidadService;
        this.registroVueloDiarioRepository = registroVueloDiarioRepository;
        this.vueloRecursoRepository = vueloRecursoRepository;
        this.usuarioRepository = usuarioRepository;
        this.loteRepository = loteRepository;
        this.detalleRepository = detalleRepository;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Plantilla
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public byte[] generarPlantillaRestaurante() {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("Plantilla");
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.BLUE_GREY.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // NUEVO: justo antes de "Row header = sheet.createRow(0);"
            // Celda en formato TEXTO ("@") para la columna Celular. Sin esto,
            // Excel interpreta un valor que empieza con "+" (ej. +51987654321)
            // como el inicio de una fórmula y descarta el "+", dejando solo
            // el número — lo que luego hace fallar la validación al subir el
            // archivo. Con formato texto, Excel guarda el valor tal cual se
            // escribe.
            CellStyle celularStyle = wb.createCellStyle();
            celularStyle.setDataFormat(wb.createDataFormat().getFormat("@"));

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 22 * 256);
            }

            // Ejemplo 1: pasajero individual (PNR único)
            escribirFilaEjemplo(sheet.createRow(1),
                    "JUAN", "PEREZ RAMOS", "ABC123", "juan.perez@correo.com",
                    "+51987654321", "1", "SI", "SI", "NO");

            // Ejemplo 2: grupo de 3 con el mismo PNR — el correo/celular y la
            // cantidad de pax SOLO se llenan en la PRIMERA fila del grupo (titular).
            escribirFilaEjemplo(sheet.createRow(2),
                    "MARIA", "LOPEZ TORRES", "XYZ789", "maria.lopez@correo.com",
                    "+51911222333", "3", "SI", "NO", "SI");
            escribirFilaEjemplo(sheet.createRow(3),
                    "CARLOS", "LOPEZ TORRES", "XYZ789", "", "", "", "", "", "");
            escribirFilaEjemplo(sheet.createRow(4),
                    "ANA", "LOPEZ TORRES", "XYZ789", "", "", "", "", "", "");

            // NUEVO: bloque agregado aquí, después de los 4 ejemplos
            // Aplica el formato de texto a la columna Celular (índice 4) en
            // un rango amplio de filas (incluidos los ejemplos de arriba),
            // para que también cubra las filas que el usuario agregue debajo.
            final int COL_CELULAR = 4;
            final int FILAS_FORMATO_TEXTO = 500;
            for (int i = 1; i <= FILAS_FORMATO_TEXTO; i++) {
                Row row = sheet.getRow(i);
                if (row == null) row = sheet.createRow(i);
                Cell celda = row.getCell(COL_CELULAR);
                if (celda == null) celda = row.createCell(COL_CELULAR);
                celda.setCellStyle(celularStyle);
            }

            Sheet instrucciones = wb.createSheet("Instrucciones");
            String[] lineas = {
                    "CÓMO LLENAR ESTA PLANTILLA",
                    "",
                    "1. Una fila = un pasajero. Nombres y Apellidos van en columnas separadas.",
                    "2. PNR: 6 caracteres alfanuméricos en mayúsculas (ej: ABC123).",
                    "3. Pasajeros que viajan juntos con el mismo PNR forman un GRUPO:",
                    "   - En la PRIMERA fila del grupo (el titular) coloque correo, celular",
                    "     (opcional) y la cantidad de pax para el restaurante.",
                    "   - En las filas siguientes del mismo grupo, deje correo/celular/",
                    "     cantidad en blanco — se toman automáticamente del titular.",
                    "4. 'Cant. Pax Restaurante' es la cantidad de personas que consumen el",
                    "   servicio (igual que 'cantidad de pax' en transporte), NO la cantidad",
                    "   de filas del grupo.",
                    "5. Celular es opcional; si se llena debe incluir código de país,",
                    "   formato +51987654321.",
                    "6. Desayuno/Almuerzo/Cena: escriba SI o NO.",
                    "7. No borre ni reordene las columnas de la hoja 'Plantilla'.",
                    "8. Al subir el archivo, el sistema valida cada fila; las filas con",
                    "   errores se muestran en un listado y NO detienen el resto de la carga."
            };
            for (int i = 0; i < lineas.length; i++) {
                instrucciones.createRow(i).createCell(0).setCellValue(lineas[i]);
            }
            instrucciones.setColumnWidth(0, 100 * 256);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BadRequestException("No se pudo generar la plantilla: " + e.getMessage());
        }
    }

    private void escribirFilaEjemplo(Row row, String... valores) {
        for (int i = 0; i < valores.length; i++) {
            row.createCell(i).setCellValue(valores[i]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Carga masiva
    // ═══════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public CargaMasivaAtencionResponse cargarRestauranteDesdeExcel(
            MultipartFile archivo, Long registroVueloDiarioId, Long vueloRecursoId,List<String> ccDestinos, String firmaPasajero, Long usuarioId,String idempotencyKey) {

        if (archivo == null || archivo.isEmpty()) {
            throw new BadRequestException("Debe adjuntar un archivo Excel (.xlsx)");
        }
        String filename = archivo.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".xlsx")) {
            throw new BadRequestException("Solo se aceptan archivos .xlsx");
        }

        Usuario agente = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado: " + usuarioId));

        RegistroVueloDiario registroDiario = registroVueloDiarioRepository.findById(registroVueloDiarioId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "Registro diario no encontrado: " + registroVueloDiarioId));
        if (registroDiario.getActive() == null || !registroDiario.getActive()) {
            throw new BadRequestException("El registro diario no está activo");
        }

        // Bloqueo pesimista del recurso — SOLO para el pre-check de
        // capacidad total de abajo (rápido, de lectura). A propósito NO se
        // sostiene durante la creación de las atenciones: esa parte ahora
        // ocurre en background, fila por fila (ver
        // AtencionCargaMasivaCreadorAsyncImpl), donde cada asignación de
        // servicio toma su propio lock breve por grupo — igual que ya
        // ocurre en la asignación individual de un agente. Sostener el
        // lock durante 300-500 creaciones bloquearía a cualquier otro
        // agente que quisiera tocar este mismo restaurante mientras dura
        // la carga completa.
        VueloRecurso vueloRecurso = vueloRecursoRepository.findByIdForUpdate(vueloRecursoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Recurso no encontrado: " + vueloRecursoId));
        if (vueloRecurso.getProveedor().getTipo() != TipoProveedorEnum.RESTAURANTE) {
            throw new BadRequestException("El recurso seleccionado no es un restaurante");
        }
        if (!vueloRecurso.getRegistroVueloDiario().getId().equals(registroVueloDiarioId)) {
            throw new BadRequestException("El restaurante no pertenece al registro diario indicado");
        }
        if (vueloRecurso.getEstado() == null || vueloRecurso.getEstado() != 1) {
            throw new BadRequestException("El restaurante no está activo: " + vueloRecurso.getProveedor().getNombre());
        }

        // ── 1 y 2. Parsear filas y agrupar por PNR (titular = primera fila
        // del grupo) — lógica compartida con previsualizarRestauranteDesdeExcel,
        // para que el preview del modal coincida exactamente con lo que se
        // procesa acá. ──────────────────────────────────────────────────
        ResultadoParseo resultadoParseo = parsearYAgruparPorPnr(archivo);
        List<GrupoValido> gruposValidos = resultadoParseo.gruposValidos();
        List<String> erroresValidacion = resultadoParseo.erroresValidacion();

        if (gruposValidos.isEmpty()) {
            throw new BadRequestException(
                    "El archivo no contiene filas válidas para procesar. Errores encontrados: "
                            + String.join(" | ", erroresValidacion));
        }

        // ── 3. Validar capacidad total — pre-check rápido de "falla
        // rápido" (fail fast), NO reserva capacidad todavía. La reserva
        // real ocurre en la fase 2, cuando AtencionCargaMasivaCreadorAsyncImpl
        // llama a asignarServicios() por grupo (mismo mecanismo que la
        // asignación individual). Este pre-check solo evita que el agente
        // espere a que corra todo el background para enterarse de que el
        // archivo pide más cupo del que existe. ─────────────────────────
        int totalPaxSolicitado = gruposValidos.stream()
                .mapToInt(g -> g.titular().paxRestaurante())
                .sum();
        disponibilidadService.validarDisponibilidadGeneral(vueloRecurso, totalPaxSolicitado);

        // ── 4. Armar las filas del lote (SIN crear atenciones todavía) ──
        // Se guardan los datos crudos de cada fila (nombre/apellido/PNR/
        // correo/celular, y para la fila titular también los datos del
        // servicio de restaurante) en estado POR_CREAR. La creación real
        // de las Atenciones ocurre en background — ver
        // AtencionCargaMasivaCreadorAsyncImpl.crearAtencionesAsync(),
        // disparado por el controller después de que esta transacción
        // (rápida) confirme.
        List<CargaMasivaDetalle> detallesLote = new ArrayList<>();
        int totalPasajerosValidos = 0;

        for (GrupoValido grupo : gruposValidos) {
            String grupoId = grupo.filas().size() > 1 ? UUID.randomUUID().toString() : null;

            for (int idx = 0; idx < grupo.filas().size(); idx++) {
                FilaExcel fila = grupo.filas().get(idx);
                boolean esTitular = idx == 0;
                String correoFinal = (fila.correo() != null && !fila.correo().isBlank())
                        ? fila.correo() : grupo.titular().correo();
                String celularFinal = (fila.celular() != null && !fila.celular().isBlank())
                        ? fila.celular() : grupo.titular().celular();
                celularFinal = (celularFinal != null && !celularFinal.isBlank()) ? celularFinal : null;

                CargaMasivaDetalle.CargaMasivaDetalleBuilder detalleBuilder = CargaMasivaDetalle.builder()
                        .grupoId(grupoId)
                        .esTitular(esTitular)
                        .pnr(grupo.pnr())
                        .nombre(fila.nombre().toUpperCase())
                        .apellido(fila.apellido().toUpperCase())
                        .nombreCompleto(fila.nombre() + " " + fila.apellido())
                        .correo(correoFinal)
                        .celular(celularFinal)
                        .estado(EstadoDetalleLoteEnum.POR_CREAR);

                if (esTitular) {
                    // El servicio de restaurante se asigna UNA sola vez, al
                    // titular del grupo — igual que hacía antes la carga
                    // síncrona. Se guardan esos datos acá para que la fase 2
                    // (async) pueda construir el ServicioAsignadoRequest sin
                    // tener que releer el Excel.
                    detalleBuilder
                            .paxRestaurante(grupo.titular().paxRestaurante())
                            .desayuno(grupo.titular().desayuno())
                            .almuerzo(grupo.titular().almuerzo())
                            .cena(grupo.titular().cena());
                }

                detallesLote.add(detalleBuilder.build());
                totalPasajerosValidos++;
            }
        }

        // ── 5. Guardar el lote y sus detalles ────────────────────────────
        // NUEVO — correos CC y firma de conformidad del modal previo a la
        // carga, para que VoucherLoteOrchestratorImpl los replique en cada
        // voucher del lote (misma lógica que el modal individual del agente).
        String ccDestinosJson = serializarCcDestinos(ccDestinos);
        String firmaPasajeroFinal = (firmaPasajero != null && !firmaPasajero.isBlank())
                ? firmaPasajero.trim() : null;

        CargaMasivaLote lote = CargaMasivaLote.builder()
                .loteId(UUID.randomUUID().toString())
                .tipoServicio(TipoDetalleEnum.RESTAURANTE)
                .vuelo(registroDiario.getVueloItinerario())
                .registroVueloDiario(registroDiario)
                .vueloRecurso(vueloRecurso)
                .cargadoPor(agente)
                .totalPasajeros(totalPasajerosValidos)
                .totalGrupos(gruposValidos.size())
                .estado(EstadoLoteEnum.CREANDO_ATENCIONES)
                .erroresValidacionJson(String.join(" | ", erroresValidacion))
                .ccDestinosJson(ccDestinosJson)
                .firmaPasajero(firmaPasajeroFinal)
                .idempotencyKey(idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null)
                .build();
        // NUEVO — si dos requests con la misma idempotencyKey llegan casi
        // simultáneos, el índice único de la BD rechaza el segundo insert.
        // En vez de fallar con un 500, recuperamos el lote que ganó la
        // carrera y lo devolvemos.
        try {
            lote = loteRepository.save(lote);
        } catch (DataIntegrityViolationException dive) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                CargaMasivaLote existente = loteRepository.findByIdempotencyKey(idempotencyKey.trim())
                        .orElseThrow(() -> dive);
                log.info("[CargaMasivaAtencion] Carrera detectada en idempotencyKey {} — devolviendo lote {} ya creado",
                        idempotencyKey, existente.getLoteId());
                return aRespuesta(existente);
            }
            throw dive;
        }

        for (CargaMasivaDetalle d : detallesLote) {
            d.setLote(lote);
        }
        detalleRepository.saveAll(detallesLote);

        log.info("[CargaMasivaAtencion] Lote {} guardado (fase 1 en cola): {} pasajero(s) en {} grupo(s), " +
                        "{} error(es) de validación — creación de atenciones ocurrirá en background",
                lote.getLoteId(), totalPasajerosValidos, gruposValidos.size(), erroresValidacion.size());

        return new CargaMasivaAtencionResponse(
                lote.getLoteId(), totalPasajerosValidos, gruposValidos.size(), erroresValidacion);
    }

    // ═══════════════════════════════════════════════════════════════════
    // NUEVO — Idempotencia
    // ═══════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Optional<CargaMasivaAtencionResponse> buscarLotePorIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return loteRepository.findByIdempotencyKey(idempotencyKey.trim()).map(this::aRespuesta);
    }

    private CargaMasivaAtencionResponse aRespuesta(CargaMasivaLote lote) {
        String json = lote.getErroresValidacionJson();
        List<String> errores = (json == null || json.isBlank())
                ? List.of()
                : Arrays.asList(json.split(" \\| "));
        return new CargaMasivaAtencionResponse(
                lote.getLoteId(), lote.getTotalPasajeros(), lote.getTotalGrupos(), errores);
    }

    // ═══════════════════════════════════════════════════════════════════
    // NUEVO — Previsualización (sin crear nada)
    // ═══════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public CargaMasivaPreviewResponse previsualizarRestauranteDesdeExcel(
            MultipartFile archivo, Long registroVueloDiarioId, Long vueloRecursoId) {

        if (archivo == null || archivo.isEmpty()) {
            throw new BadRequestException("Debe adjuntar un archivo Excel (.xlsx)");
        }
        String filename = archivo.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".xlsx")) {
            throw new BadRequestException("Solo se aceptan archivos .xlsx");
        }

        RegistroVueloDiario registroDiario = registroVueloDiarioRepository.findById(registroVueloDiarioId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "Registro diario no encontrado: " + registroVueloDiarioId));
        if (registroDiario.getActive() == null || !registroDiario.getActive()) {
            throw new BadRequestException("El registro diario no está activo");
        }

        // Sin bloqueo pesimista (findByIdForUpdate) — esto es solo lectura,
        // no reserva ni crea nada; el bloqueo real ocurre recién al confirmar,
        // en cargarRestauranteDesdeExcel.
        VueloRecurso vueloRecurso = vueloRecursoRepository.findById(vueloRecursoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Recurso no encontrado: " + vueloRecursoId));
        if (vueloRecurso.getProveedor().getTipo() != TipoProveedorEnum.RESTAURANTE) {
            throw new BadRequestException("El recurso seleccionado no es un restaurante");
        }
        if (!vueloRecurso.getRegistroVueloDiario().getId().equals(registroVueloDiarioId)) {
            throw new BadRequestException("El restaurante no pertenece al registro diario indicado");
        }
        if (vueloRecurso.getEstado() == null || vueloRecurso.getEstado() != 1) {
            throw new BadRequestException("El restaurante no está activo: " + vueloRecurso.getProveedor().getNombre());
        }

        ResultadoParseo resultado = parsearYAgruparPorPnr(archivo);
        List<GrupoValido> gruposValidos = resultado.gruposValidos();
        List<String> erroresValidacion = resultado.erroresValidacion();

        // A diferencia de cargarRestauranteDesdeExcel, AQUÍ NO se lanza
        // excepción si no hay grupos válidos — el modal debe poder mostrar
        // "0 pasajeros válidos" + el detalle de errores, en vez de fallar.
        int totalPaxSolicitado = gruposValidos.stream()
                .mapToInt(g -> g.titular().paxRestaurante())
                .sum();
        int totalPasajeros = gruposValidos.stream()
                .mapToInt(g -> g.filas().size())
                .sum();

        // Capacidad disponible ACTUAL del restaurante — solo informativo:
        // no bloquea el preview. La validación que sí bloquea sigue
        // ocurriendo al confirmar, en cargarRestauranteDesdeExcel, con el
        // recurso bloqueado (findByIdForUpdate) para evitar sobreventa.
        Integer capacidadDisponible = disponibilidadService.obtenerDisponibilidad(registroVueloDiarioId)
                .restaurantes().stream()
                .filter(r -> r.vueloRecursoId().equals(vueloRecursoId))
                .map(DisponibilidadResponse.RecursoDisponibleResponse::capacidadDisponible)
                .findFirst()
                .orElse(null);

        boolean excedeCapacidad = capacidadDisponible != null && totalPaxSolicitado > capacidadDisponible;

        List<CargaMasivaPreviewResponse.GrupoPreview> gruposPreview = gruposValidos.stream()
                .map(g -> new CargaMasivaPreviewResponse.GrupoPreview(
                        g.pnr(),
                        g.titular().nombre() + " " + g.titular().apellido(),
                        g.titular().correo(),
                        g.titular().celular(),
                        g.filas().size(),
                        g.filas().stream().map(f -> f.nombre() + " " + f.apellido()).toList(),
                        g.titular().paxRestaurante(),
                        g.titular().desayuno(),
                        g.titular().almuerzo(),
                        g.titular().cena()))
                .toList();

        return new CargaMasivaPreviewResponse(
                totalPasajeros,
                gruposValidos.size(),
                gruposPreview,
                erroresValidacion,
                capacidadDisponible,
                totalPaxSolicitado,
                excedeCapacidad);
    }

    /**
     * NUEVO — extraído de cargarRestauranteDesdeExcel para reutilizarse
     * también en previsualizarRestauranteDesdeExcel: parsea el Excel y
     * agrupa las filas válidas por PNR, descartando (con su respectivo
     * mensaje en erroresValidacion) los grupos cuyo titular no traiga
     * correo o cantidad de pax.
     */
    private ResultadoParseo parsearYAgruparPorPnr(MultipartFile archivo) {
        List<String> erroresValidacion = new ArrayList<>();
        List<FilaExcel> filasValidas = parsearExcel(archivo, erroresValidacion);

        LinkedHashMap<String, List<FilaExcel>> grupos = new LinkedHashMap<>();
        for (FilaExcel f : filasValidas) {
            grupos.computeIfAbsent(f.pnr(), k -> new ArrayList<>()).add(f);
        }

        List<GrupoValido> gruposValidos = new ArrayList<>();
        for (Map.Entry<String, List<FilaExcel>> e : grupos.entrySet()) {
            String pnr = e.getKey();
            List<FilaExcel> filas = e.getValue();
            FilaExcel titular = filas.get(0);

            if (titular.correo() == null || titular.correo().isBlank()) {
                erroresValidacion.add("PNR " + pnr + ": la primera fila del grupo debe traer el correo del titular — grupo omitido");
                continue;
            }
            if (titular.paxRestaurante() == null || titular.paxRestaurante() < 1) {
                erroresValidacion.add("PNR " + pnr + ": la primera fila del grupo debe traer la cantidad de pax para el restaurante — grupo omitido");
                continue;
            }
            gruposValidos.add(new GrupoValido(pnr, filas, titular));
        }
        return new ResultadoParseo(gruposValidos, erroresValidacion);
    }

    @Override
    @Transactional(readOnly = true)
    public LoteEstadoResponse consultarEstadoLote(String loteId) {
        CargaMasivaLote lote = loteRepository.findByLoteId(loteId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Lote no encontrado: " + loteId));

        List<CargaMasivaDetalle> detalles = detalleRepository.findByLote_IdOrderByIdAsc(lote.getId());
        List<LoteEstadoResponse.DetalleItem> items = detalles.stream()
                .map(d -> new LoteEstadoResponse.DetalleItem(
                        d.getCorrelativo(), d.getPnr(), d.getNombreCompleto(),
                        Boolean.TRUE.equals(d.getEsTitular()), d.getEstado().name(), d.getMensajeError()))
                .toList();

        return new LoteEstadoResponse(
                lote.getLoteId(), lote.getEstado().name(), lote.getTotalPasajeros(),
                lote.getProcesados(), lote.getExitosos(), lote.getFallidos(), items);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Parseo del Excel
    // ═══════════════════════════════════════════════════════════════════

    private List<FilaExcel> parsearExcel(MultipartFile archivo, List<String> erroresValidacion) {
        List<FilaExcel> filas = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(archivo.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            int totalFilas = sheet.getLastRowNum();

            for (int i = 1; i <= totalFilas; i++) {
                Row row = sheet.getRow(i);
                if (row == null || esFilaVacia(row)) continue;
                int filaUsuario = i + 1;

                try {
                    String nombre = getCellString(row, 0);
                    String apellido = getCellString(row, 1);
                    String pnr = getCellString(row, 2).toUpperCase();
                    String correo = getCellString(row, 3);
                    String celular = getCellString(row, 4);
                    String paxStr = getCellString(row, 5);
                    boolean desayuno = esSi(getCellString(row, 6));
                    boolean almuerzo = esSi(getCellString(row, 7));
                    boolean cena = esSi(getCellString(row, 8));

                    if (nombre.isEmpty() || apellido.isEmpty()) {
                        erroresValidacion.add("Fila " + filaUsuario + ": nombres y apellidos son obligatorios");
                        continue;
                    }
                    if (!PnrValidator.isValid(pnr)) {
                        erroresValidacion.add("Fila " + filaUsuario + ": PNR inválido '" + pnr
                                + "' — debe tener 6 caracteres alfanuméricos");
                        continue;
                    }
                    if (!correo.isBlank() && !EMAIL_PATTERN.matcher(correo).matches()) {
                        erroresValidacion.add("Fila " + filaUsuario + ": correo inválido '" + correo + "'");
                        continue;
                    }
                    if (!celular.isBlank() && !TELEFONO_PATTERN.matcher(celular).matches()) {
                        erroresValidacion.add("Fila " + filaUsuario
                                + ": celular inválido — use formato +51987654321");
                        continue;
                    }
                    Integer pax = null;
                    if (!paxStr.isBlank()) {
                        try {
                            pax = Integer.parseInt(paxStr.trim());
                            if (pax < 1) {
                                erroresValidacion.add("Fila " + filaUsuario + ": la cantidad de pax debe ser mayor a 0");
                                continue;
                            }
                        } catch (NumberFormatException nfe) {
                            erroresValidacion.add("Fila " + filaUsuario + ": cantidad de pax inválida '" + paxStr + "'");
                            continue;
                        }
                    }

                    filas.add(new FilaExcel(nombre, apellido, pnr,
                            correo.isBlank() ? null : correo,
                            celular.isBlank() ? null : celular,
                            pax, desayuno, almuerzo, cena));

                } catch (Exception ex) {
                    erroresValidacion.add("Fila " + filaUsuario + ": " + ex.getMessage());
                }
            }
        } catch (IOException e) {
            throw new BadRequestException("Error leyendo el archivo Excel: " + e.getMessage());
        }
        return filas;
    }

    /**
     * NUEVO — serializa la lista de correos CC del modal previo a la carga
     * en un string delimitado por CargaMasivaLote.CC_SEPARATOR para
     * guardarla en CargaMasivaLote.ccDestinosJson. Filtra vacíos/duplicados.
     * Ver VoucherLoteOrchestratorImpl.deserializarCcDestinos para el
     * parseo inverso.
     */
    private String serializarCcDestinos(List<String> ccDestinos) {
        if (ccDestinos == null || ccDestinos.isEmpty()) return null;
        LinkedHashSet<String> limpios = new LinkedHashSet<>();
        for (String correo : ccDestinos) {
            if (correo != null && !correo.isBlank()) {
                limpios.add(correo.trim());
            }
        }
        return limpios.isEmpty() ? null : String.join(CargaMasivaLote.CC_SEPARATOR, limpios);
    }

    private boolean esSi(String valor) {
        return "SI".equalsIgnoreCase(valor) || "SÍ".equalsIgnoreCase(valor) || "S".equalsIgnoreCase(valor);
    }

    private String getCellString(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                yield val == Math.floor(val) ? String.valueOf((long) val) : String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            // Puede ocurrir si el usuario escribió un valor que Excel interpretó
            // como fórmula (ej. "+51987654321" sin la celda en formato texto).
            // El resultado cacheado puede ser numérico o texto según el caso —
            // consultamos el tipo cacheado en vez de asumir texto, para no
            // lanzar una excepción al leer el archivo.
            case FORMULA -> switch (cell.getCachedFormulaResultType()) {
                case STRING -> cell.getStringCellValue().trim();
                case NUMERIC -> {
                    double val = cell.getNumericCellValue();
                    yield val == Math.floor(val) ? String.valueOf((long) val) : String.valueOf(val);
                }
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> "";
            };
            default -> "";
        };
    }

    private boolean esFilaVacia(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK
                    && !getCellString(row, c).isBlank()) {
                return false;
            }
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Tipos internos
    // ═══════════════════════════════════════════════════════════════════

    private record FilaExcel(
            String nombre, String apellido, String pnr, String correo, String celular,
            Integer paxRestaurante, boolean desayuno, boolean almuerzo, boolean cena) {}

    private record GrupoValido(String pnr, List<FilaExcel> filas, FilaExcel titular) {}

    /** NUEVO — resultado compartido de parsearYAgruparPorPnr(). */
    private record ResultadoParseo(List<GrupoValido> gruposValidos, List<String> erroresValidacion) {}
}

