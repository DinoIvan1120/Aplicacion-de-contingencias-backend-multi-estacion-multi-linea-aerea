package com.saasa.contingencias.domain.dto.response;
import java.math.BigDecimal;
public record ServicioProveedorResponse(Long id, Long proveedorId, String tipoServicio, String descripcion, BigDecimal monto, Integer estado) {}
