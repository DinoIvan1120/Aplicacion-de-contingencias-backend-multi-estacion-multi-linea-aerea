package com.saasa.contingencias.domain.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO de respuesta para el endpoint GET /api/v1/reportes/resumen.
 * Contiene KPIs y datos serializables para los tres gráficos del módulo.
 */
public record ResumenReporteResponse(

        /** Cantidad total de atenciones activas en el rango */
        long totalAtenciones,

        /** Suma total de montos de atenciones activas */
        BigDecimal importeTotal,

        /** Promedio de importe por atención */
        BigDecimal promedioImporte,

        /** Distribución de cantidad e importe agrupada por tipo de servicio */
        List<TipoServicioStat> distribucionPorTipo,

        /** Cantidad de atenciones agrupada por fecha (para gráfico de barras) */
        List<FechaStat> atencionPorFecha,

        /** Importe total agrupado por fecha (para gráfico de área) */
        List<FechaImporteStat> importePorFecha,

        /** Desglose por proveedor individual (para tabla de estadística) */
        List<ProveedorStat> distribucionPorProveedor

) {

    /** Stat por tipo de servicio: HOTEL / TRANSPORTE / RESTAURANTE */
    public record TipoServicioStat(
            String tipo,
            long cantidad,
            BigDecimal importe
    ) {}

    /** Cantidad de atenciones en un día */
    public record FechaStat(
            String fecha,    // ISO date: "2025-05-01"
            long cantidad
    ) {}

    /** Importe total de un día */
    public record FechaImporteStat(
            String fecha,    // ISO date: "2025-05-01"
            BigDecimal importe
    ) {}

    /** Stat por proveedor individual */
    public record ProveedorStat(
            String tipo,             // HOTEL | TRANSPORTE | RESTAURANTE
            String proveedorNombre,
            long cantidad,
            BigDecimal importe
    ) {}
}
