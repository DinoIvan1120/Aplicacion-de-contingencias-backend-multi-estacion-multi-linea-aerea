package com.saasa.contingencias.domain.dto.response;
import java.math.BigDecimal;
import java.time.LocalDateTime;
public record AtencionResponse(Long id, String numeroCorrelativo, Long vueloId, String codigoVuelo, String nombre, String apellido, String pnr, String correo, BigDecimal montoTotal, String codigoAutorizacion, String pdfUrl, String estado, String atendidoPorNombre, LocalDateTime createdAt
,String firmaPasajero, Boolean firmaConforme, LocalDateTime firmaFecha,String origenFirma,String origenFirmaRo) {}
