package com.saasa.contingencias.domain.enumeration;

/**
 * NUEVO — Distingue quién es el firmante de la conformidad digital
 * guardada en Atencion.firmaPasajero:
 *  - PASAJERO: el propio pasajero firmó en el modal de confirmación
 *    de la vista individual del agente.
 *  - AGENTE_LOTE: el agente autorizó una carga masiva completa; el
 *    texto guardado es el nombre de quien autorizó, no del pasajero.
 */
public enum OrigenFirmaEnum { PASAJERO, AGENTE_LOTE }
