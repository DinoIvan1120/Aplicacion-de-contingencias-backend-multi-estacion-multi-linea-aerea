package com.saasa.contingencias.domain.dto.response;
import java.time.LocalDateTime;
public record UsuarioResponse(Long id, String nombre, String apellido, String correo, String documento, String codigoEmpleado, String rol, Integer estado, LocalDateTime createdAt) {}
