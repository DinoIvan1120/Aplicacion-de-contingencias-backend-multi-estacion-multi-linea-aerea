package com.saasa.contingencias.domain.dto.response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
public record ServicioAsignadoResponse(Long id, Long atencionId, Long servicioProveedorId, String tipoDetalle, Integer cantidad, BigDecimal montoUnitario, BigDecimal montoSubtotal, LocalDateTime asignadoEn,LocalDate fechaIngreso, LocalDate fechaSalida) {}
