package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.domain.enumeration.ClaveIconoModoEnum;
import com.saasa.contingencias.domain.model.IconoModo;
import com.saasa.contingencias.domain.repository.IconoModoRepository;
import com.saasa.contingencias.service.IIconoModoService;
import com.saasa.contingencias.service.IS3StorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IconoModoServiceImpl implements IIconoModoService {

    private final IconoModoRepository iconoModoRepository;
    private final IS3StorageService s3StorageService;

    public IconoModoServiceImpl(IconoModoRepository iconoModoRepository,
                                IS3StorageService s3StorageService) {
        this.iconoModoRepository = iconoModoRepository;
        this.s3StorageService = s3StorageService;
    }

    @Override
    @Transactional
    public String subirIcono(String clave, byte[] bytes, String contentType) {
        String claveNormalizada = validarClave(clave);
        IconoModo icono = obtenerOCrear(claveNormalizada);

        String extension = "image/png".equals(contentType) ? ".png" : ".jpg";
        String objectKey = "iconos-modo/" + claveNormalizada + extension;

        // subirObjeto devuelve la key COMPLETA (con prefijo de ambiente) —
        // se persiste tal cual, igual que en Estacion.fotoKey y
        // LineaAerea.logoKey, para que obtenerUrlIcono() no se
        // desincronice de la key real en S3.
        String keyGuardada = s3StorageService.subirObjeto(bytes, objectKey, contentType);

        icono.setIconoKey(keyGuardada);
        iconoModoRepository.save(icono);

        return s3StorageService.generarUrlFirmada(keyGuardada);
    }

    @Override
    public String obtenerUrlIcono(String clave) {
        String claveNormalizada = validarClave(clave);
        return iconoModoRepository.findByClave(claveNormalizada)
                .map(IconoModo::getIconoKey)
                .filter(key -> key != null && !key.isBlank())
                .map(s3StorageService::generarUrlFirmada)
                .orElse(null);
    }

    private IconoModo obtenerOCrear(String clave) {
        return iconoModoRepository.findByClave(clave)
                .orElseGet(() -> IconoModo.builder().clave(clave).build());
    }

    private String validarClave(String clave) {
        try {
            return ClaveIconoModoEnum.valueOf(clave.toUpperCase()).name();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException(
                    "Clave de ícono inválida: " + clave + " (valores válidos: GESTIONAR, OPERAR)");
        }
    }
}