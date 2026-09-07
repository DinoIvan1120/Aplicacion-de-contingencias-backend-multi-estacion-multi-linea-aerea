package com.saasa.contingencias.service;

public interface IIconoModoService {
    /**
     * Sube el ícono de una opción y persiste su key en S3.
     * @param clave "GESTIONAR" u "OPERAR" (ver ClaveIconoModoEnum).
     * @return URL firmada temporal (15 min) del ícono recién subido.
     */
    String subirIcono(String clave, byte[] bytes, String contentType);

    /**
     * @return URL firmada temporal (15 min), o null si esa opción todavía
     *         no tiene ícono subido.
     */
    String obtenerUrlIcono(String clave);
}