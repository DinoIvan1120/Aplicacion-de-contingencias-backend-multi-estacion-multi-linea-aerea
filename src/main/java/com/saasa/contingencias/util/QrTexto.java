package com.saasa.contingencias.util;

import java.text.Normalizer;

/**
 * Prepara el texto que se codifica en los QR de verificación de vouchers.
 *
 * PROBLEMA RESUELTO:
 * El QR se generaba en UTF-8, pero muchas apps de escaneo de celular
 * ignoran la marca de codificación del QR (ECI) y adivinan otra. Así,
 * "RODRÍGUEZ" se leía como "RODR虾UEZ" y "Náutica" como "N醬tica".
 *
 * SOLUCIÓN:
 * Se quitan las tildes y diéresis (Á→A, Ñ→N, Ü→U) y se descarta cualquier
 * otro carácter fuera de ASCII imprimible, conservando los saltos de línea.
 * Un QR solo con ASCII se lee igual en cualquier app.
 */
public final class QrTexto {

    private QrTexto() {
    }

    public static String paraQr(String texto) {
        if (texto == null) {
            return "";
        }
        String sinMarcas = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return sinMarcas.replaceAll("[^\\x20-\\x7E\\n]", "");
    }
}