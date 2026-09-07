package com.saasa.contingencias.config.security;

/**
 * Par (estación, línea aérea) de acceso de un usuario. Reemplaza al
 * antiguo eje único "estacionId" para reflejar que, desde la extensión
 * multi-estación / multi-línea aérea, la línea aérea también es un eje de
 * aislamiento dentro de cada estación.
 *
 * lineaAereaId == null significa "todas las líneas aéreas de esa
 * estación" (p. ej. un Administrador de Estación sin restricción de
 * línea). Un usuario con lista de scopes vacía es Administrador Global.
 */
public record ScopeEstacionLinea(Long estacionId, Long lineaAereaId) {
}