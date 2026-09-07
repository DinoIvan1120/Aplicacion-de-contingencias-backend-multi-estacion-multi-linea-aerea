package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.domain.dto.response.ReporteVoucherResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
/**
 * Responsabilidad única: construir el archivo Excel de reportes.
 * No conoce repositorios ni lógica de negocio.
 */
@Component
public class ReporteExcelBuilder {

    private static final String[] HEADERS = {
            "Correlativo", "PNR", "Pasajero", "Vuelo", "Fecha Vuelo",
            "Hotel", "Total Hotel", "Transporte", "Total Transporte",
            "Restaurante", "Total Restaurante", "Total General",
            "Estado", "Generado Por", "Rol", "Fecha Creación"
    };

    // En ReporteExcelBuilder.java
    public byte[] build(List<ReporteVoucherResponse> datos,
                         LocalDate desde, LocalDate hasta) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Reporte");
            escribirMetadata(wb, sheet, desde, hasta);  // ← agregar
            escribirCabecera(wb, sheet);                // ← agregar
            escribirDatos(wb, sheet, datos);            // ← agregar
            autoSizeColumns(sheet);                     // ← agregar
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Error al construir Excel", e);
        }
    }

    private void escribirMetadata(Workbook wb, Sheet sheet,
                                  LocalDate desde, LocalDate hasta) {
        Row row = sheet.createRow(0);
        CellStyle style = createMetaStyle(wb);
        Cell cell = row.createCell(0);
        String rango = (desde !=null && hasta !=null)
                ? "Periodo: " + desde + "al " + hasta
                : "Periodo: Todos los registros";
        cell.setCellValue("Reporte SAASA — " + rango);
        //cell.setCellValue("Reporte SAASA — Período: " + desde + " al " + hasta);
        cell.setCellStyle(style);
    }

    private void escribirCabecera(Workbook wb, Sheet sheet) {
        Row row = sheet.createRow(1);
        CellStyle style = createHeaderStyle(wb);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(HEADERS[i]);
            cell.setCellStyle(style);
        }
    }

    private void escribirDatos(Workbook wb, Sheet sheet,
                               List<ReporteVoucherResponse> datos) {
        CellStyle dataStyle = createDataStyle(wb);
        CellStyle moneyStyle = createMoneyStyle(wb);
        int rowNum = 2;
        for (ReporteVoucherResponse r : datos) {
            Row row = sheet.createRow(rowNum++);
            setCellData(row,  0, r.correlativo(),   dataStyle);
            setCellData(row,  1, r.pnr(),           dataStyle);
            setCellData(row,  2, r.pasajero(),      dataStyle);
            setCellData(row,  3, r.vuelo(),         dataStyle);
            setCellData(row,  4, r.fechaVuelo() != null ? r.fechaVuelo().toString() : "", dataStyle);
            setCellData(row,  5, r.hotel(),         dataStyle);
            setCellMoney(row, 6, r.hotelTotal(),    moneyStyle);
            setCellData(row,  7, r.transporte(),    dataStyle);
            setCellMoney(row, 8, r.transporteTotal(), moneyStyle);
            setCellData(row,  9, r.restaurante(),   dataStyle);
            setCellMoney(row, 10, r.restauranteTotal(), moneyStyle);
            setCellMoney(row, 11, r.total(),        moneyStyle);
            setCellData(row, 12, r.estado(),        dataStyle);
            setCellData(row, 13, r.generadoPor(),   dataStyle);
            setCellData(row, 14, r.rolGenerador(),  dataStyle);
            setCellData(row, 15, r.createdAt() != null ? r.createdAt().toString() : "", dataStyle);
        }
    }

    private void autoSizeColumns(Sheet sheet) {
        for (int i = 0; i < HEADERS.length; i++) {  // ahora son 16 columnas
            sheet.autoSizeColumn(i);
        }
    }

    private void setCellData(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private void setCellMoney(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value.doubleValue() : 0.0);
        cell.setCellStyle(style);
    }

    private CellStyle createHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private CellStyle createMetaStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 12);
        s.setFont(f);
        return s;
    }

    private CellStyle createDataStyle(Workbook wb) {
        return wb.createCellStyle();
    }

    private CellStyle createMoneyStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        DataFormat fmt = wb.createDataFormat();
        s.setDataFormat(fmt.getFormat("#,##0.00"));
        return s;
    }
}
