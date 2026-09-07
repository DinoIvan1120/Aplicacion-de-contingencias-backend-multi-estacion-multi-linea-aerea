package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.dto.response.CargaMasivaResponse;
import com.saasa.contingencias.domain.dto.response.VueloResponse;
import com.saasa.contingencias.domain.enumeration.ContingenciaEnum;
import com.saasa.contingencias.domain.enumeration.EstadoVueloEnum;
import com.saasa.contingencias.domain.mapping.VueloMapper;
import com.saasa.contingencias.domain.model.*;
import com.saasa.contingencias.domain.repository.*;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.service.IVueloExcelService;
import com.saasa.contingencias.util.DateTimeUtil;
import com.saasa.contingencias.util.IataCodigo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementación de la carga masiva de vuelos desde Excel.
 *
 * Extraído de VueloServiceImpl (métodos cargarDesdeExcel, normalizarContingencia,
 * getCellString, getCellDate, esFilaVacia).
 *
 * Depende de IVueloService para crear cada vuelo individual, respetando
 * las validaciones de negocio ya existentes en VueloServiceImpl.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VueloExcelServiceImpl implements IVueloExcelService {

    // ✅ Solo repositorios — sin dependencia de IVueloService
    private final VueloRepository vueloRepository;
    private final UsuarioRepository usuarioRepository;
    private final VueloMapper vueloMapper;
    private final com.saasa.contingencias.config.security.EstacionContext estacionContext;
    private final EstacionRepository estacionRepository;
    private final LineaAereaRepository lineaAereaRepository;
    private final EstacionLineaAereaRepository estacionLineaAereaRepository;

    @Override
    @Transactional
    public CargaMasivaResponse cargarDesdeExcel(MultipartFile archivo, Long usuarioId, Long estacionIdSolicitada, Long lineaAereaIdSolicitada) {
        // El Excel completo pertenece a UNA sola estación+línea aérea (el
        // contexto de trabajo activo del topbar) — deja de auto-crearse una
        // LineaAerea nueva por cada texto de la columna "aerolínea".
        ScopeEstacionLinea contexto = estacionContext.resolverContextoActivo(estacionIdSolicitada, lineaAereaIdSolicitada);
        Long estacionId = contexto.estacionId();
        Estacion estacion = estacionRepository.findById(estacionId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Estación no encontrada: " + estacionId));
        LineaAerea lineaDelContexto = lineaAereaRepository.findById(contexto.lineaAereaId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Línea aérea no encontrada: " + contexto.lineaAereaId()));
        List<VueloResponse> registrados = new ArrayList<>();
        List<String> errores = new ArrayList<>();

        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "Usuario no encontrado: " + usuarioId));

        // Fecha actual en Lima para validar que no se registren vuelos con fecha pasada
        LocalDate hoyLima = DateTimeUtil.hoyEnLima();
        log.info("[VueloExcel] Iniciando carga masiva. Fecha Lima: {}", hoyLima);

        try (Workbook wb = new XSSFWorkbook(archivo.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            int totalFilas = sheet.getLastRowNum();
            log.info("[VueloExcel] Iniciando carga masiva: {} filas", totalFilas);

            for (int i = 1; i <= totalFilas; i++) {
                Row row = sheet.getRow(i);
                if (row == null || esFilaVacia(row)) continue;

                // Número de fila legible para el usuario (empieza en 1)
                int filaUsuario = i + 1;

                try {
                    String codigoVuelo = getCellString(row, 0).toUpperCase();
                    String aerolinea  = getCellString(row, 1);
                    String origen     = getCellString(row, 2).toUpperCase();
                    String destino    = getCellString(row, 3).toUpperCase();
                    LocalDate fecha   = getCellDate(row, 4);
                    ContingenciaEnum contingencia = normalizarContingencia(getCellString(row, 5));
                    String obs        = getCellString(row, 6);

                    if (codigoVuelo.isEmpty() || aerolinea.isEmpty()) {
                        errores.add("Fila " + filaUsuario + ": código de vuelo y aerolínea son obligatorios");
                        continue;
                    }
                    if (!lineaDelContexto.getNombre().equalsIgnoreCase(aerolinea.trim())) {
                        errores.add("Fila " + filaUsuario + ": la aerolínea '" + aerolinea
                                + "' no coincide con el contexto de trabajo activo ('" + lineaDelContexto.getNombre()
                                + "'). Todas las filas del archivo deben ser de la misma línea aérea seleccionada en el topbar.");
                        continue;
                    }
                    if (fecha == null) {
                        errores.add("Fila " + filaUsuario + ": fecha inválida o vacía");
                        continue;
                    }
                    if (!IataCodigo.isValid(origen)) {
                        errores.add("Fila " + filaUsuario + ": código IATA origen inválido '" + origen + "'");
                        continue;
                    }
                    if (!IataCodigo.isValid(destino)) {
                        errores.add("Fila " + filaUsuario + ": código IATA destino inválido '" + destino + "'");
                        continue;
                    }

                    // ── 3. Validación de fecha pasada (usando hora Lima) ─────
                    // Se compara contra hoyEnLima() para evitar registrar vuelos
                    // de días anteriores que ya no pueden tener contingencias nuevas.
                    if (fecha.isBefore(hoyLima)) {
                        errores.add("Fila " + filaUsuario + ": el vuelo " + codigoVuelo +
                                " tiene fecha " + fecha + " anterior a hoy (" + hoyLima +
                                " hora Lima) — no se puede registrar");
                        log.warn("[VueloExcel] Fila {}: fecha pasada ({} < {})",
                                filaUsuario, fecha, hoyLima);
                        continue;
                    }

                    // - Validación de duplicado: mismo código + misma fecha de vuelo
                    // Alineado con la restricción de VueloServiceImpl.create()
                    if(vueloRepository.existsByCodigoVueloAndFechaVuelo(codigoVuelo,fecha)){
                        errores.add("Fila " + (i+1) + ": el vuelo " + codigoVuelo
                                + "ya existe para la fecha " + fecha
                                + " - se omite para evitar duplicado");
                        log.warn("[VueloExcel] Fila {}: duplicado detectado ({}, {}) ", i + 1, codigoVuelo
                                ,fecha);
                        continue;
                    }

                    // ✅ Construye y guarda directamente — sin llamar a IVueloService
                    Vuelo v = Vuelo.builder()
                            .aerolinea(aerolinea)
                            .codigoVuelo(codigoVuelo.trim())
                            .origen(origen)
                            .destino(destino)
                            .fechaVuelo(fecha)
                            .tipoContingencia(contingencia)
                            .observaciones(obs)
                            .estado(EstadoVueloEnum.ACTIVO)
                            .creadoPor(usuario)
                            .build();
                    LineaAerea linea = lineaDelContexto;
                    v.setEstacionId(estacionId);
                    v.setLineaAereaId(linea.getId());

                    Vuelo guardado = vueloRepository.save(v);
                    registrados.add(vueloMapper.toResponse(guardado));
                    log.debug("[VueloExcel] Fila {}: vuelo {} creado ok", i + 1, codigoVuelo);

                } catch (Exception ex) {
                    String msg = "Fila " + filaUsuario + ": " + ex.getMessage();
                    errores.add(msg);
                    log.warn("[VueloExcel] Error procesando {}", msg);
                }
            }

            if (!errores.isEmpty()) {
                log.warn("[VueloExcel] Carga completada con {} errores: {}", errores.size(), errores);
            }
            log.info("[VueloExcel] Finalizada: {} vuelos creados, {} errores",
                    registrados.size(), errores.size());

        } catch (Exception e) {
            throw new BadRequestException("Error procesando el archivo Excel: " + e.getMessage());
        }
        return new CargaMasivaResponse(registrados, errores);
    }

    /** @deprecated ya no se usa: la línea aérea de la carga masiva es siempre la del contexto activo (ver cargarDesdeExcel). */
    @Deprecated
    private LineaAerea resolverLineaAerea(Estacion estacion, String aerolineaTexto, Map<String, LineaAerea> cache) {
        String nombre = aerolineaTexto == null ? "" : aerolineaTexto.trim();
        LineaAerea linea = cache.computeIfAbsent(nombre.toUpperCase(java.util.Locale.ROOT), k ->
                lineaAereaRepository.findByNombreIgnoreCase(nombre).orElseGet(() ->
                        lineaAereaRepository.save(LineaAerea.builder()
                                .codigoIata(generarCodigoIataProvisional(nombre)).nombre(nombre).estado(1).build())));
        boolean habilitada = estacionLineaAereaRepository
                .findByEstacionIdAndLineaAereaId(estacion.getId(), linea.getId())
                .map(rel -> rel.getEstado() == 1).orElse(false);
        if (!habilitada) {
            estacionLineaAereaRepository.save(EstacionLineaAerea.builder()
                    .estacion(estacion).lineaAerea(linea).estado(1).build());
        }
        return linea;
    }

    // Usado por resolverLineaAerea() de este archivo
    private String generarCodigoIataProvisional(String nombreAerolinea) {
        String base = nombreAerolinea.trim().toUpperCase().replaceAll("[^A-Z]", "");
        String prefijo = (base.length() >= 3 ? base.substring(0, 3) : (base + "XXX").substring(0, 3));
        String candidato = prefijo;
        int sufijo = 1;
        while (lineaAereaRepository.existsByCodigoIata(candidato)) {
            candidato = prefijo.substring(0, 2) + sufijo;
            sufijo++;
        }
        return candidato;
    }

    // ── Helpers (sin cambios) ─────────────────────────────────────────────────

    private ContingenciaEnum normalizarContingencia(String texto) {
        if (texto == null || texto.isBlank()) {
            log.warn("[VueloExcel] Contingencia vacía, usando CANCELACION por defecto");
            return ContingenciaEnum.CANCELACION;
        }
        String norm = texto.trim().toUpperCase()
                .replace("Á","A").replace("É","E").replace("Í","I")
                .replace("Ó","O").replace("Ú","U").replace("Ñ","N")
                .replaceAll("\\s+", " ");
        try { return ContingenciaEnum.valueOf(norm); }
        catch (IllegalArgumentException ignored) {}
        if (norm.contains("CANCEL"))  return ContingenciaEnum.CANCELACION;
        if (norm.contains("DEMOR"))   return ContingenciaEnum.DEMORA;
        if (norm.contains("REPROG"))  return ContingenciaEnum.REPROGRAMADO;
        if (norm.contains("PROGRAM")) return ContingenciaEnum.PROGRAMADO;
        if (norm.contains("RETRASO")) return ContingenciaEnum.DEMORA;
        if (norm.contains("POSTERG")) return ContingenciaEnum.REPROGRAMADO;
        log.warn("[VueloExcel] Contingencia no reconocida: '{}' → usando CANCELACION", texto);
        return ContingenciaEnum.CANCELACION;
    }

    private String getCellString(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                yield val == Math.floor(val)
                        ? String.valueOf((long) val)
                        : String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getStringCellValue().trim();
            default      -> "";
        };
    }

    private LocalDate getCellDate(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
                return cell.getLocalDateTimeCellValue().toLocalDate();
            if (cell.getCellType() == CellType.STRING) {
                String str = cell.getStringCellValue().trim();
                for (String pattern : List.of("dd/MM/yyyy","yyyy-MM-dd","dd-MM-yyyy","MM/dd/yyyy")) {
                    try { return LocalDate.parse(str, DateTimeFormatter.ofPattern(pattern)); }
                    catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("[VueloExcel] Error leyendo fecha ({},{}): {}", row.getRowNum(), col, e.getMessage());
        }
        return null;
    }

    private boolean esFilaVacia(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK
                    && !getCellString(row, c).isEmpty()) return false;
        }
        return true;
    }
}