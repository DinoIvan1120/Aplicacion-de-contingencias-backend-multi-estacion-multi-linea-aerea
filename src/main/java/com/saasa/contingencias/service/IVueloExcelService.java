package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.response.CargaMasivaResponse;
import com.saasa.contingencias.domain.dto.response.VueloResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Servicio de carga masiva de vuelos desde archivo Excel.
 *
 * Responsabilidad única: parsear un archivo .xlsx, validar cada fila
 * y crear los vuelos correspondientes.
 *
 * Extraído de VueloServiceImpl donde convivía con la lógica de
 * gestión de vuelos individuales.
 */
public interface IVueloExcelService {

    /**
     * Lee un archivo Excel y crea los vuelos válidos encontrados.
     *
     * Formato esperado del Excel (columnas):
     *   0 - Código de vuelo
     *   1 - Aerolínea
     *   2 - Origen  (IATA 3 letras)
     *   3 - Destino (IATA 3 letras)
     *   4 - Fecha   (dd/MM/yyyy o formato Excel)
     *   5 - Tipo de contingencia (texto libre, se normaliza al enum)
     *   6 - Observaciones (opcional)
     *
     * Las filas con errores se incluyen en carga masiva errores
     * y NO detienen el proceso; las filas correctas se registran igualmente.
     *
     * @param archivo   Archivo .xlsx cargado por el usuario
     * @param usuarioId ID del usuario que realiza la carga (para auditoría)
     * @return Resultado con vuelos creados y lista de errores por fila
     */
    CargaMasivaResponse cargarDesdeExcel(MultipartFile archivo, Long usuarioId, Long estacionId, Long lineaAereaId);
}

