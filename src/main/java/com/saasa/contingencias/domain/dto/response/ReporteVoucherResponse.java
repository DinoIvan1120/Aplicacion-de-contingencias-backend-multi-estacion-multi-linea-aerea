package com.saasa.contingencias.domain.dto.response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
public record ReporteVoucherResponse(
        String correlativo,
        String pnr,
        String pasajero,
        String vuelo,
        LocalDate fechaVuelo,           // ✅ NUEVO - Fecha del vuelo
        String hotel,
        BigDecimal hotelTotal,
        String transporte,
        BigDecimal transporteTotal,
        String restaurante,
        BigDecimal restauranteTotal,
        BigDecimal total,
        String estado,
        String generadoPor,
        String rolGenerador,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        /** NUEVO - Voucher grupal: distinto de null si esta atención comparte PDF con otros pasajeros.*/
        String grupoId,
        Boolean esTitularGrupo,
        String nombreTitularGrupo)
{}
