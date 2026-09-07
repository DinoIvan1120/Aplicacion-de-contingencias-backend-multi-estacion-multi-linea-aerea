package com.saasa.contingencias.service;

/**
 * Contrato para la generación de correlativos de atención.
 *
 * Principio de responsabilidad única (SRP):
 *   La generación del correlativo es una razón de cambio
 *   independiente del ciclo de vida de la Atención.
 *   Si mañana cambia el formato "SGC-" o se necesita otro
 *   prefijo por tipo de atención, solo cambia esta clase.
 *
 * Principio de inversión de dependencias (DIP):
 *   AtencionServiceImpl depende de esta interfaz,
 *   no de la implementación concreta.
 */
public interface ICorrelativoService {
    String generarCorrelativo(Long estacionId, Long lineaAereaId);
}

