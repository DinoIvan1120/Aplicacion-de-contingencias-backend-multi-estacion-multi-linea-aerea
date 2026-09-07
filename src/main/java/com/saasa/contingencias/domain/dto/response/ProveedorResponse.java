package com.saasa.contingencias.domain.dto.response;
import java.time.LocalDateTime;
public record ProveedorResponse(Long id, String tipo, String nombre, String ruc, String direccion, String telefono, String correo, Integer estado, LocalDateTime createdAt) {}
