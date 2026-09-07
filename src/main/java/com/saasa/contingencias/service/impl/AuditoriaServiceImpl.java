package com.saasa.contingencias.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saasa.contingencias.domain.dto.response.AuditoriaResponse;
import com.saasa.contingencias.domain.mapping.AuditoriaMapper;
import com.saasa.contingencias.domain.model.Auditoria;
import com.saasa.contingencias.domain.model.Usuario;
import com.saasa.contingencias.config.security.ContextoActivoHolder;
import com.saasa.contingencias.config.security.EstacionContext;
import com.saasa.contingencias.config.security.ScopeEstacionLinea;
import com.saasa.contingencias.domain.repository.AuditoriaRepository;
import com.saasa.contingencias.domain.repository.UsuarioRepository;
import com.saasa.contingencias.service.IAuditoriaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.criteria.Predicate;

/**
 * Servicio de auditoría mejorado.
 */
@Slf4j
@Service
public class AuditoriaServiceImpl implements IAuditoriaService {

    private static final int    MAX_ROWS_EXCEL = 10_000;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final AuditoriaRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;
    private final ObjectMapper objectMapper;
    private final AuditoriaMapper auditoriaMapper;
    private final EstacionContext estacionContext;

    public AuditoriaServiceImpl(
            AuditoriaRepository auditoriaRepository,
            UsuarioRepository usuarioRepository,
            ObjectMapper objectMapper,
            AuditoriaMapper auditoriaMapper,
            EstacionContext estacionContext
    ) {
        this.auditoriaRepository = auditoriaRepository;
        this.usuarioRepository = usuarioRepository;
        this.objectMapper = objectMapper;
        this.auditoriaMapper = auditoriaMapper;
        this.estacionContext = estacionContext;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MÉTODO 1: registrar (firma original - COMPATIBILIDAD)
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void registrar(
            Long usuarioId,
            String accion,
            String modulo,
            String ipOrigen,
            Object detalle
    ) {
        try {
            // Convertir detalle a String JSON
            String detalleJson = detalle instanceof String
                    ? (String) detalle
                    : objectMapper.writeValueAsString(detalle);

            // Buscar usuario
            Usuario usuario = null;
            if (usuarioId != null) {
                usuario = usuarioRepository.findById(usuarioId).orElse(null);
            }

            // Si ipOrigen viene como parámetro, usarlo; si no, capturar automáticamente
            String ip = (ipOrigen != null && !ipOrigen.isEmpty())
                    ? ipOrigen
                    : obtenerIpOrigen();

            // Estación/línea a estampar en la auditoría: se resuelve con el
            // mismo fallback que usa la lectura (scope fijo del usuario si
            // el header del topbar no vino), en vez de leer el holder crudo.
            // Si no hay contexto HTTP ni scope fijo (acción de sistema, job
            // programado), se cae a null/null en vez de reventar el registro.
            ScopeEstacionLinea contexto = resolverContextoParaAuditoria();

            // Crear registro de auditoría
            Auditoria auditoria = Auditoria.builder()
                    .usuario(usuario)
                    .accion(accion)
                    .modulo(modulo)
                    .ipOrigen(ip)
                    .detalle(detalleJson)
                    .userAgent(obtenerUserAgent())
                    .estacionId(contexto.estacionId())
                    .lineaAereaId(contexto.lineaAereaId())
                    .build();

            auditoriaRepository.save(auditoria);

            log.info("✅ Auditoría registrada: usuario={}, acción={}", usuarioId, accion);

        } catch (Exception e) {
            log.error("❌ Error registrando auditoría: {}", e.getMessage(), e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MÉTODO 2: registrar (firma completa - NUEVO)
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void registrar(
            Long usuarioId,
            String accion,
            String modulo,
            String detalle,
            String entidadTipo,
            Long entidadId,
            String entidadNombre
    ) {
        try {
            // Obtener información del request HTTP
            String ipOrigen = obtenerIpOrigen();
            String userAgent = obtenerUserAgent();

            // Buscar usuario
            Usuario usuario = null;
            if (usuarioId != null) {
                usuario = usuarioRepository.findById(usuarioId).orElse(null);
            }

            // Mismo criterio de resolución que en la firma anterior — ver
            // resolverContextoParaAuditoria().
            ScopeEstacionLinea contexto = resolverContextoParaAuditoria();

            // Crear registro de auditoría
            Auditoria auditoria = Auditoria.builder()
                    .usuario(usuario)
                    .accion(accion)
                    .modulo(modulo)
                    .ipOrigen(ipOrigen)
                    .detalle(detalle)
                    .entidadTipo(entidadTipo)
                    .entidadId(entidadId)
                    .entidadNombre(entidadNombre)
                    .userAgent(userAgent)
                    .estacionId(contexto.estacionId())
                    .lineaAereaId(contexto.lineaAereaId())
                    .build();

            auditoriaRepository.save(auditoria);

            log.info("✅ Auditoría registrada: usuario={}, acción={}, entidad={}[{}]",
                    usuarioId, accion, entidadTipo, entidadId);

        } catch (Exception e) {
            log.error("❌ Error registrando auditoría: {}", e.getMessage(), e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MÉTODO 3: findAll (firma original - COMPATIBILIDAD)
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Page<Auditoria> findAll(Pageable pageable, Long usuarioId) {
        Specification<Auditoria> spec = usuarioId != null
                ? scopeSpec().and((root, query, cb) -> cb.equal(root.get("usuario").get("id"), usuarioId))
                : scopeSpec();
        return auditoriaRepository.findAll(spec, pageable);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MÉTODO 4: listar (NUEVO - con response enriquecido)
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Page<AuditoriaResponse> listar(Pageable pageable) {
        Page<Auditoria> auditorias = auditoriaRepository.findAll(scopeSpec(), pageable);
        return auditorias.map(auditoriaMapper::toResponse);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MÉTODO 5: buscarPorUsuario (NUEVO)
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Page<AuditoriaResponse> buscarPorUsuario(Long usuarioId, Pageable pageable) {
        Specification<Auditoria> spec = scopeSpec()
                .and((root, query, cb) -> cb.equal(root.get("usuario").get("id"), usuarioId));
        Page<Auditoria> auditorias = auditoriaRepository.findAll(spec, pageable);
        return auditorias.map(auditoriaMapper::toResponse);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MÉTODO 6: buscarPorEntidad (NUEVO)
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Page<AuditoriaResponse> buscarPorEntidad(String entidadTipo, Long entidadId, Pageable pageable) {
        Specification<Auditoria> spec = scopeSpec().and((root, query, cb) -> cb.and(
                cb.equal(root.get("entidadTipo"), entidadTipo),
                cb.equal(root.get("entidadId"), entidadId)));
        Page<Auditoria> auditorias = auditoriaRepository.findAll(spec, pageable);
        return auditorias.map(auditoriaMapper::toResponse);
    }


    // ═══════════════════════════════════════════════════════════════════════
    // MÉTODO 6b: buscarConFiltros combinados (Specification)
    // ═══════════════════════════════════════════════════════════════════════
    @Override
    @Transactional(readOnly = true)
    public Page<AuditoriaResponse> buscarConFiltros(String buscar, Long usuarioId,
                                                    String modulo, String accion,
                                                    LocalDate fechaDesde, LocalDate fechaHasta,
                                                    Pageable pageable) {
        Specification<Auditoria> spec = buildSpec(buscar, usuarioId, modulo, accion, fechaDesde, fechaHasta);
        return auditoriaRepository.findAll(spec, pageable).map(auditoriaMapper::toResponse);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MÉTODO 7: exportarExcel (NUEVO)
    // ═══════════════════════════════════════════════════════════════════════
    @Override
    @Transactional(readOnly = true)
    public byte[] exportarExcel(String buscar, Long usuarioId, String modulo,
                                String accion, LocalDate fechaDesde, LocalDate fechaHasta) {

        // ── Construir Specification con los filtros ──────────────────────────
        Specification<Auditoria> spec = buildSpec(buscar, usuarioId, modulo, accion, fechaDesde, fechaHasta);

        List<Auditoria> registros = auditoriaRepository
                .findAll(spec,
                        PageRequest.of(0, MAX_ROWS_EXCEL,
                                Sort.by("creadoEn").descending()))
                .getContent();

        // ── Generar Excel ────────────────────────────────────────────────────
        try (XSSFWorkbook wb  = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("Log de Auditoría");

            // Fila de metadatos
            Row metaRow = sheet.createRow(0);
            CellStyle metaStyle = createMetaStyle(wb);
            String meta = String.format("Generado: %s | Total registros: %d",
                    LocalDateTime.now().format(FMT), registros.size());
            Cell metaCell = metaRow.createCell(0);
            metaCell.setCellValue(meta);
            metaCell.setCellStyle(metaStyle);
            sheet.addMergedRegion(
                    new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 8));

            // Cabecera
            String[] cols = {
                    "Fecha", "Usuario", "Rol",
                    "Módulo", "Acción",
                    "Entidad (Tipo)", "Entidad (Nombre)",
                    "Detalle", "IP Origen"
            };
            CellStyle headerStyle = createHeaderStyle(wb);
            Row headerRow = sheet.createRow(1);
            for (int i = 0; i < cols.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(cols[i]);
                cell.setCellStyle(headerStyle);
            }

            // Datos
            CellStyle dataStyle  = createDataStyle(wb);
            CellStyle wrapStyle  = createWrapStyle(wb);
            int rowNum = 2;

            for (Auditoria r : registros) {
                Row row = sheet.createRow(rowNum++);

                String usuarioNombre = r.getUsuario() != null
                        ? r.getUsuario().getNombre() + " " + r.getUsuario().getApellido()
                        : "Sistema";
                String usuarioRol = r.getUsuario() != null && r.getUsuario().getRol() != null
                        ? r.getUsuario().getRol().name()
                        : "—";

                setCellData(row, 0,
                        r.getCreadoEn() != null ? r.getCreadoEn().format(FMT) : "—", dataStyle);
                setCellData(row, 1, usuarioNombre, dataStyle);
                setCellData(row, 2, usuarioRol,    dataStyle);
                setCellData(row, 3, r.getModulo()  != null ? r.getModulo()  : "—", dataStyle);
                setCellData(row, 4, r.getAccion()  != null ? r.getAccion()  : "—", dataStyle);
                setCellData(row, 5, r.getEntidadTipo()   != null ? r.getEntidadTipo()   : "—", dataStyle);
                setCellData(row, 6, r.getEntidadNombre() != null ? r.getEntidadNombre() : "—", dataStyle);
                setCellData(row, 7, r.getDetalle()       != null ? r.getDetalle()       : "—", wrapStyle);
                setCellData(row, 8, r.getIpOrigen()      != null ? r.getIpOrigen()      : "—", dataStyle);
            }

            // Autosize — la col 7 (Detalle) puede ser muy larga, la fijamos
            for (int i = 0; i < cols.length; i++) {
                if (i == 7) {
                    sheet.setColumnWidth(7, 60 * 256);
                } else {
                    sheet.autoSizeColumn(i);
                }
            }

            wb.write(baos);
            log.info("✅ Excel de auditoría generado: {} registros", registros.size());
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("❌ Error generando Excel de auditoría", e);
            throw new RuntimeException("Error generando Excel de auditoría: " + e.getMessage(), e);
        }
    }

    /**
     * Resuelve estación+línea para estampar en un registro de auditoría,
     * usando el mismo fallback al scope fijo del usuario que ya usa la
     * lectura (resolverContextoActivo(), no la variante "Lectura" que es
     * más tolerante). Si no hay contexto de trabajo activo — job programado,
     * acción de sistema sin request HTTP, usuario sin línea fija y sin
     * header — cae a null/null en vez de propagar la excepción, para no
     * perder el registro de auditoría completo.
     */
    private ScopeEstacionLinea resolverContextoParaAuditoria() {
        try {
            ScopeEstacionLinea contexto = estacionContext.resolverContextoActivo();
            return contexto != null ? contexto : new ScopeEstacionLinea(null, null);
        } catch (Exception e) {
            log.warn("⚠️ No se pudo resolver estación/línea para auditoría, se guarda sin scope: {}", e.getMessage());
            return new ScopeEstacionLinea(null, null);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helper: construcción de Specification compartida
    // ═══════════════════════════════════════════════════════════════════════
    private Specification<Auditoria> buildSpec(String buscar, Long usuarioId, String modulo,
                                               String accion, LocalDate fechaDesde, LocalDate fechaHasta) {
        return scopeSpec().and((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (usuarioId != null)
                predicates.add(cb.equal(root.get("usuario").get("id"), usuarioId));
            if (modulo != null && !modulo.isBlank())
                predicates.add(cb.equal(cb.upper(root.get("modulo")), modulo.toUpperCase()));
            if (accion != null && !accion.isBlank())
                predicates.add(cb.equal(cb.upper(root.get("accion")), accion.toUpperCase()));
            if (buscar != null && !buscar.isBlank()) {
                String like = "%" + buscar.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("detalle")),       like),
                        cb.like(cb.lower(root.get("entidadNombre")), like)
                ));
            }
            if (fechaDesde != null)
                predicates.add(cb.greaterThanOrEqualTo(root.get("creadoEn"), fechaDesde.atStartOfDay()));
            if (fechaHasta != null)
                predicates.add(cb.lessThan(root.get("creadoEn"), fechaHasta.plusDays(1).atStartOfDay()));

            return cb.and(predicates.toArray(new Predicate[0]));
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers de estilos POI
    // ═══════════════════════════════════════════════════════════════════════
    private void setCellData(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private Specification<Auditoria> scopeSpec() {
        ScopeEstacionLinea contexto = estacionContext.resolverContextoActivoLectura();
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("estacionId"), contexto.estacionId()));
            if (contexto.lineaAereaId() != null) {
                predicates.add(cb.equal(root.get("lineaAereaId"), contexto.lineaAereaId()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private CellStyle createHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setBorderBottom(BorderStyle.THIN);
        s.setAlignment(HorizontalAlignment.CENTER);
        return s;
    }

    private CellStyle createMetaStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setItalic(true);
        f.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private CellStyle createDataStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        return s;
    }

    private CellStyle createWrapStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setWrapText(true);
        return s;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MÉTODOS AUXILIARES
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * ✅ Obtener IP real del cliente
     */
    private String obtenerIpOrigen() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            if (attributes == null) {
                return "SYSTEM";
            }

            HttpServletRequest request = attributes.getRequest();

            // Intentar obtener IP real (considerando proxies)
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("X-Real-IP");
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getRemoteAddr();
            }

            // Si viene lista de IPs, tomar la primera
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }

            // Convertir localhost IPv6 a IPv4
            if ("0:0:0:0:0:0:0:1".equals(ip)) {
                ip = "127.0.0.1";
            }

            return ip;

        } catch (Exception e) {
            log.warn("⚠️ No se pudo obtener IP origen: {}", e.getMessage());
            return "UNKNOWN";
        }
    }

    /**
     * ✅ Obtener User Agent del navegador
     */
    private String obtenerUserAgent() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            if (attributes == null) {
                return "SYSTEM";
            }

            HttpServletRequest request = attributes.getRequest();
            String userAgent = request.getHeader("User-Agent");

            return userAgent != null ? userAgent : "UNKNOWN";

        } catch (Exception e) {
            log.warn("⚠️ No se pudo obtener User-Agent: {}", e.getMessage());
            return "UNKNOWN";
        }
    }

}