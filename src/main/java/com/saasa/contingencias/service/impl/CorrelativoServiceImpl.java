package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.model.CorrelativoSequence;
import com.saasa.contingencias.domain.model.Estacion;
import com.saasa.contingencias.domain.model.LineaAerea;
import com.saasa.contingencias.domain.repository.CorrelativoSequenceRepository;
import com.saasa.contingencias.domain.repository.EstacionRepository;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import com.saasa.contingencias.service.ICorrelativoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementación de ICorrelativoService.
 *
 * Estrategia: cada llamada a generarCorrelativo() hace un
 * INSERT en correlativo_sequence y obtiene el id generado
 * por AUTO_INCREMENT de MySQL.  Este mecanismo es:
 *
 *  ✅ Atómico       — MySQL garantiza unicidad del id.
 *  ✅ Multi-instancia — funciona con N réplicas JVM sin
 *                      ningún synchronized ni bloqueo Java.
 *  ✅ Persistente   — los ids no se reinician al reiniciar
 *                      el servidor (a diferencia de AtomicLong).
 *  ✅ Sin huecos en auditoría — los correlativos de atenciones
 *                      anuladas no se reutilizan.
 *
 * Responsabilidad única: SOLO formatear el número de secuencia.
 * Si el formato "SGC-%09d" cambia, este es el único archivo a editar.
 */
@Service
public class CorrelativoServiceImpl implements ICorrelativoService {

    private static final String FORMATO = "SGC-%s-%s-%06d";
    private final CorrelativoSequenceRepository correlativoSequenceRepository;
    private final EstacionRepository estacionRepository;
    private final LineaAereaRepository lineaAereaRepository;

    public CorrelativoServiceImpl(CorrelativoSequenceRepository correlativoSequenceRepository, EstacionRepository estacionRepository, LineaAereaRepository lineaAereaRepository) {
        this.correlativoSequenceRepository = correlativoSequenceRepository;
        this.estacionRepository = estacionRepository;
        this.lineaAereaRepository = lineaAereaRepository;
    }

    @Override
    @Transactional
    public String generarCorrelativo(Long estacionId, Long lineaAereaId) {
        if (estacionId == null || lineaAereaId == null) {
            throw new IllegalArgumentException(
                    "estacionId y lineaAereaId son obligatorios para generar un correlativo");
        }

        Estacion estacion = estacionRepository.findById(estacionId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Estación no encontrada: " + estacionId));
        LineaAerea lineaAerea = lineaAereaRepository.findById(lineaAereaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Línea aérea no encontrada: " + lineaAereaId));

        correlativoSequenceRepository.incrementarYObtener(estacionId, lineaAereaId);
        Long numero = correlativoSequenceRepository.obtenerUltimoNumeroGenerado(estacionId, lineaAereaId);

        return String.format(FORMATO, estacion.getCodigoIata(), lineaAerea.getCodigoIata(), numero);
    }
}