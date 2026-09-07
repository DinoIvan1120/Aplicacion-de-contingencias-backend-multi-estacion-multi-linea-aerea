package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.config.exception.RecursoNoEncontradoException;
import com.saasa.contingencias.domain.dto.request.LineaAereaRequest;
import com.saasa.contingencias.domain.dto.response.LineaAereaResponse;
import com.saasa.contingencias.domain.mapping.LineaAereaMapper;
import com.saasa.contingencias.domain.model.LineaAerea;
import com.saasa.contingencias.domain.repository.LineaAereaRepository;
import com.saasa.contingencias.service.ILineaAereaService;
import com.saasa.contingencias.service.IPdfGeneratorService;
import com.saasa.contingencias.service.IS3StorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Catálogo global de líneas aéreas (Documento Funcional Multi-Estación
 * v1.1, sección 5.2). El vínculo de qué línea opera en qué estación se
 * gestiona en IEstacionService, no aquí.
 */
@Service
public class LineaAereaServiceImpl implements ILineaAereaService {

    private final LineaAereaRepository lineaAereaRepository;
    private final LineaAereaMapper lineaAereaMapper;
    private final IS3StorageService s3StorageService;
    private final IPdfGeneratorService pdfGeneratorService;

    public LineaAereaServiceImpl(LineaAereaRepository lineaAereaRepository, LineaAereaMapper lineaAereaMapper,
                                 IS3StorageService s3StorageService,
                                 IPdfGeneratorService pdfGeneratorService) {
        this.lineaAereaRepository = lineaAereaRepository;
        this.lineaAereaMapper = lineaAereaMapper;
        this.s3StorageService = s3StorageService;
        this.pdfGeneratorService = pdfGeneratorService;
    }

    @Override
    public List<LineaAereaResponse> findAll(Integer estado) {
        List<LineaAerea> lineas = estado == null
                ? lineaAereaRepository.findAll()
                : lineaAereaRepository.findAll().stream().filter(l -> l.getEstado().equals(estado)).toList();
        return lineaAereaMapper.toResponseList(lineas);
    }

    @Override
    public LineaAereaResponse findById(Long id) {
        return lineaAereaMapper.toResponse(obtenerLineaAerea(id));
    }

    @Override
    @Transactional
    public LineaAereaResponse create(LineaAereaRequest request) {
        String codigo = request.codigoIata().trim().toUpperCase();
        if (lineaAereaRepository.existsByCodigoIata(codigo)) {
            throw new BadRequestException("Ya existe una línea aérea con el código IATA: " + codigo);
        }
        LineaAerea nueva = LineaAerea.builder()
                .codigoIata(codigo)
                .nombre(request.nombre().trim())
                .estado(1)
                .build();
        return lineaAereaMapper.toResponse(lineaAereaRepository.save(nueva));
    }

    @Override
    @Transactional
    public LineaAereaResponse update(Long id, LineaAereaRequest request) {
        LineaAerea lineaAerea = obtenerLineaAerea(id);
        String codigo = request.codigoIata().trim().toUpperCase();
        if (!codigo.equals(lineaAerea.getCodigoIata()) && lineaAereaRepository.existsByCodigoIata(codigo)) {
            throw new BadRequestException("Ya existe una línea aérea con el código IATA: " + codigo);
        }
        lineaAerea.setCodigoIata(codigo);
        lineaAerea.setNombre(request.nombre().trim());
        return lineaAereaMapper.toResponse(lineaAereaRepository.save(lineaAerea));
    }

    @Override
    @Transactional
    public void changeEstado(Long id, Integer estado) {
        LineaAerea lineaAerea = obtenerLineaAerea(id);
        lineaAerea.setEstado(estado);
        lineaAereaRepository.save(lineaAerea);
    }

    @Override
    @Transactional
    public LineaAereaResponse subirLogo(Long id, byte[] logoBytes, String contentType) {
        LineaAerea lineaAerea = obtenerLineaAerea(id);

        String objectKey = "logos/" + lineaAerea.getCodigoIata() + ".png";

        // subirObjeto devuelve la key COMPLETA (con prefijo de ambiente)
        // bajo la que realmente quedó el archivo en S3. Se persiste esa
        // key tal cual — nunca una reconstruida a mano — para que
        // obtenerUrlLogo() apunte siempre al objeto correcto.
        String keyGuardada = s3StorageService.subirObjeto(logoBytes, objectKey, contentType);

        lineaAerea.setLogoKey(keyGuardada);
        LineaAereaResponse response = lineaAereaMapper.toResponse(lineaAereaRepository.save(lineaAerea));

        // Como la key en S3 es siempre la misma (logos/{codigoIata}.png),
        // el logo viejo puede seguir cacheado en memoria en
        // PdfGeneratorServiceImpl. Lo invalidamos para que el próximo
        // voucher/PDF descargue la versión recién subida.
        pdfGeneratorService.invalidarCacheLogo(id);

        return response;
    }

    @Override
    public String obtenerUrlLogo(Long id) {
        LineaAerea lineaAerea = obtenerLineaAerea(id);
        if (lineaAerea.getLogoKey() == null || lineaAerea.getLogoKey().isBlank()) {
            return null;
        }
        return s3StorageService.generarUrlFirmada(lineaAerea.getLogoKey());
    }

    private LineaAerea obtenerLineaAerea(Long id) {
        return lineaAereaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Línea aérea no encontrada: " + id));
    }
}
