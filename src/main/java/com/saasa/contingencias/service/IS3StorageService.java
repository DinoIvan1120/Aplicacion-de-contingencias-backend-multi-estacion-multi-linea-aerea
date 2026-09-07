package com.saasa.contingencias.service;

public interface IS3StorageService {
    String subirPdf(byte[] pdfBytes, String correlativo);
    byte[] descargarPdf(String pdfUrl);
    /**
     * ✅ AGREGAR ESTE MÉTODO
     * Genera URL firmada temporal para descarga (15 minutos)
     */
    String generarUrlFirmada(String s3Key);
    void eliminarPdf(String pdfUrl);

    String generarUrlConExpiracion(String objectKey, int diasExpiracion);

    /**
     * Sube un objeto genérico (ej. logo de aerolínea) a la key EXACTA que
     * se le pasa (con el prefijo de ambiente aplicado, pero SIN el
     * "vouchers/{año}/" que agrega subirPdf). Existe por separado de
     * subirPdf justamente para evitar que un caller reutilice subirPdf
     * para un asset que no es un voucher y termine con una key distinta
     * a la que después queda registrada como referencia (p. ej. en
     * LineaAerea.logoKey) — mismatch que rompe la URL firmada al
     * recuperarlo.
     *
     * @return la key completa (con prefijo de ambiente) bajo la que quedó
     *         guardado el objeto — esta es la key que se debe persistir.
     */
    String subirObjeto(byte[] bytes, String objectKey, String contentType);
}
