package com.saasa.contingencias.domain.dto.response;
import java.time.LocalDate;
import java.time.LocalDateTime;
public record VueloResponse(Long id, String aerolinea, String codigoVuelo, String origen, String destino, LocalDate fechaVuelo, String tipoContingencia, String observaciones, String estado, Long creadoPorId, String creadoPorNombre, LocalDateTime createdAt) {}
