package com.saasa.contingencias.domain.dto.response;
import java.util.List;

/**
 * estacionIds: estaciones activas del usuario. Lista vacía = Administrador
 * Global. Se expone aquí para que el frontend arme el selector de
 * estación/línea del login sin una llamada adicional.
 *
 * lineaAereaFija: true cuando el usuario tiene EXACTAMENTE un par
 * estación+línea aérea asignado (p. ej. un Líder/Agente de una sola
 * aerolínea) — en ese caso el frontend puede saltarse el selector de
 * contexto por completo, igual que antes hacía solo con la estación.
 * false para Administrador Global, usuarios con varias estaciones/líneas,
 * o un Administrador de Estación sin línea fija: todos ellos DEBEN elegir
 * el contexto de trabajo (estación+línea) en el selector del topbar.
 *
 * estacionFijaId / lineaAereaFijaId: solo se completan cuando
 * lineaAereaFija=true — evitan que el frontend tenga que adivinar o pedir
 * de nuevo el único par que ya conoce el backend.
 */
public record AuthResponse(String token, String rol, String nombre, String apellido,
                           Long expiresIn, List<Long> estacionIds, boolean lineaAereaFija,
                           Long estacionFijaId, Long lineaAereaFijaId) {}
