package com.saasa.contingencias.domain.enumeration;

/**
 * Estado global de un lote de carga masiva (carga de pasajeros + envío
 * asíncrono de vouchers). No confundir con EstadoAtencionEnum (por
 * pasajero) ni con EstadoDetalleLoteEnum (por fila del lote).
 */
public enum EstadoLoteEnum {
    CREANDO_ATENCIONES,       // Fase 1: creando las Atenciones desde el Excel (background)
    ERROR_CREACION,           // Fase 1 terminó y NINGUNA fila se pudo crear
    PROCESANDO,               // Fase 2: el envío de vouchers está en curso (background)
    COMPLETADO,               // Todos los envíos terminaron OK
    COMPLETADO_CON_ERRORES,   // Terminó, pero algún pasajero/grupo falló (en fase 1 y/o 2)
    ERROR                     // Terminó y todos los envíos fallaron
}
