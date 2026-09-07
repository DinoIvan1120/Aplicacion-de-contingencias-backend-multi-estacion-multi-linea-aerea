package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.domain.dto.response.ReporteVoucherResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReporteExcelBuilderTest {

    private ReporteExcelBuilder builder;

    private static final String[] HEADERS_ESPERADOS = {
            "Correlativo", "PNR", "Pasajero", "Vuelo", "Fecha Vuelo",
            "Hotel", "Total Hotel", "Transporte", "Total Transporte",
            "Restaurante", "Total Restaurante", "Total General",
            "Estado", "Generado Por", "Rol", "Fecha Creación"
    };

    @BeforeEach
    void setUp() {
        builder = new ReporteExcelBuilder();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // build() — estructura básica
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void build_listaVacia_generaArchivoValido() throws IOException {
        // Act
        byte[] result = builder.build(List.of(),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 31));

        // Assert — el archivo no es nulo ni vacío
        assertNotNull(result);
        assertTrue(result.length > 0);

        // Verificar que es un Excel válido legible por POI
        Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(result));
        assertNotNull(wb.getSheet("Reporte"));
        wb.close();
    }

    @Test
    void build_conDatos_cabeceraTieneNombresCorrrectos() throws IOException {
        // Arrange
        List<ReporteVoucherResponse> datos = List.of(unReporte());

        // Act
        byte[] result = builder.build(datos,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 31));

        // Assert — leer cabecera fila 1 (fila 0 es metadata)
        Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(result));
        Sheet sheet = wb.getSheet("Reporte");
        Row headerRow = sheet.getRow(1);

        for (int i = 0; i < HEADERS_ESPERADOS.length; i++) {
            Cell cell = headerRow.getCell(i);
            assertNotNull(cell, "Celda de cabecera " + i + " no debe ser nula");
            assertEquals(HEADERS_ESPERADOS[i], cell.getStringCellValue(),
                    "Cabecera columna " + i + " incorrecta");
        }
        wb.close();
    }

    @Test
    void build_conDatos_primeraFilaDatosEsCorrecta() throws IOException {
        // Arrange
        ReporteVoucherResponse reporte = unReporte();

        // Act
        byte[] result = builder.build(List.of(reporte),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 31));

        // Assert — fila 2 es la primera fila de datos
        Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(result));
        Sheet sheet = wb.getSheet("Reporte");
        Row dataRow = sheet.getRow(2);

        assertEquals("SGC-000000001", dataRow.getCell(0).getStringCellValue());
        assertEquals("ABC123", dataRow.getCell(1).getStringCellValue());
        assertEquals("Juan Perez", dataRow.getCell(2).getStringCellValue());
        wb.close();
    }

    @Test
    void build_montoNulo_usaCeroEnCeldaNumerica() throws IOException {
        // Arrange — reporte con montos nulos
        ReporteVoucherResponse reporteConNulos = new ReporteVoucherResponse(
                "SGC-000000002", "XYZ789", "Ana Torres",
                "LA2015", LocalDate.of(2025, 1, 15),
                null, null,        // hotel nulo
                null, null,        // transporte nulo
                null, null,        // restaurante nulo
                null,              // total nulo
                "ACTIVO", "Agente", "AGENTE_SAASA",
                LocalDateTime.now(), null,null,null,null
        );

        // Act — no debe lanzar NullPointerException
        byte[] result = builder.build(List.of(reporteConNulos),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 31));

        // Assert
        assertNotNull(result);
        Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(result));
        Sheet sheet = wb.getSheet("Reporte");
        Row dataRow = sheet.getRow(2);

        // Columnas de dinero deben ser 0.0, no lanzar excepción
        assertEquals(0.0, dataRow.getCell(6).getNumericCellValue());   // Total Hotel
        assertEquals(0.0, dataRow.getCell(8).getNumericCellValue());   // Total Transporte
        assertEquals(0.0, dataRow.getCell(10).getNumericCellValue());  // Total Restaurante
        assertEquals(0.0, dataRow.getCell(11).getNumericCellValue());  // Total General
        wb.close();
    }

    @Test
    void build_metadataContienePeriodo() throws IOException {
        // Act
        byte[] result = builder.build(List.of(),
                LocalDate.of(2025, 3, 1),
                LocalDate.of(2025, 3, 31));

        // Assert — fila 0 tiene el período en la celda 0
        Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(result));
        Sheet sheet = wb.getSheet("Reporte");
        String metadata = sheet.getRow(0).getCell(0).getStringCellValue();

        assertTrue(metadata.contains("2025-03-01"), "Metadata debe contener fecha inicio");
        assertTrue(metadata.contains("2025-03-31"), "Metadata debe contener fecha fin");
        wb.close();
    }

    @Test
    void build_variosRegistros_todasLasFilasEscritas() throws IOException {
        // Arrange
        List<ReporteVoucherResponse> datos = List.of(
                unReporte(), unReporte(), unReporte()
        );

        // Act
        byte[] result = builder.build(datos,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 31));

        // Assert — 3 filas de datos + 1 metadata + 1 cabecera = 5 filas
        Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(result));
        Sheet sheet = wb.getSheet("Reporte");
        assertEquals(5, sheet.getLastRowNum() + 1);
        wb.close();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ReporteVoucherResponse unReporte() {
        return new ReporteVoucherResponse(
                "SGC-000000001",
                "ABC123",
                "Juan Perez",
                "PU302",
                LocalDate.of(2025, 1, 15),
                "Hotel Costa del Sol",
                new BigDecimal("250.00"),
                "Transporte Lima",
                new BigDecimal("50.00"),
                "Restaurante Central",
                new BigDecimal("75.00"),
                new BigDecimal("375.00"),
                "ACTIVO",
                "Ana Agente",
                "AGENTE_SAASA",
                LocalDateTime.of(2025, 1, 15, 10, 30),
                null,
                null,
                null,null
        );
    }
}

