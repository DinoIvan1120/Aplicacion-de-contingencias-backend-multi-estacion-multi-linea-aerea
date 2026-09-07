package com.saasa.contingencias.domain.dto.request;
import java.time.LocalDate;
public record ReporteFilterRequest(
    String correlativo, String pnr, String nombrePasajero,
    Long vueloId, Long hotelId, Long transporteId, Long restauranteId, Long agenteId,
    LocalDate fechaDesde, LocalDate fechaHasta,String estado // NUEVO: "ACTIVO" | "ANULADO" | null (todos)
) {}
