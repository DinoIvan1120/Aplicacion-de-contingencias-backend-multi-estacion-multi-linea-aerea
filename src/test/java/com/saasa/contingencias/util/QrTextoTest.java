package com.saasa.contingencias.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test unitario de QrTexto: el texto del QR debe quedar en ASCII puro para
 * que cualquier app de escaneo lo lea igual.
 */
class QrTextoTest {

    @Test
    @DisplayName("Tildes → letras sin tilde (mayúsculas y minúsculas)")
    void paraQr_quitaTildes() {
        assertEquals("RODRIGUEZ", QrTexto.paraQr("RODRÍGUEZ"));
        assertEquals("La Rosa Nautica", QrTexto.paraQr("La Rosa Náutica"));
        assertEquals("aeiou AEIOU", QrTexto.paraQr("áéíóú ÁÉÍÓÚ"));
    }

    @Test
    @DisplayName("Ñ y diéresis → N y U")
    void paraQr_enieYDieresis() {
        assertEquals("MUNOZ", QrTexto.paraQr("MUÑOZ"));
        assertEquals("pinguino", QrTexto.paraQr("pingüino"));
    }

    @Test
    @DisplayName("Conserva los saltos de línea y el texto ASCII sin cambios")
    void paraQr_conservaSaltosYAscii() {
        String ascii = "VOUCHER: SGC-LIM-PU-000001\nPNR: ABC123\nAirportHub - Plus Ultra";
        assertEquals(ascii, QrTexto.paraQr(ascii));
    }

    @Test
    @DisplayName("Caracteres sin equivalente ASCII se descartan")
    void paraQr_descartaNoAscii() {
        assertEquals("GRUPAL  4", QrTexto.paraQr("GRUPAL · 4"));
        assertEquals("A", QrTexto.paraQr("A虾"));
    }

    @Test
    @DisplayName("Null → cadena vacía")
    void paraQr_nulo() {
        assertEquals("", QrTexto.paraQr(null));
    }

    @Test
    @DisplayName("Contenido real del voucher grupal → solo ASCII imprimible y saltos de línea")
    void paraQr_contenidoVoucher_soloAscii() {
        String qr = QrTexto.paraQr("VOUCHER GRUPAL: SGC-LIM-PU-000002\n"
                + "PASAJEROS (2): JOHNNY MR/ACERO, FABIOLA/RODRÍGUEZ ESPINOZA\n"
                + "RESTAURANTE: La Rosa Náutica Restaurant (4 cubiertos)\n"
                + "AirportHub - Plus Ultra");

        assertTrue(qr.chars().allMatch(c -> c == '\n' || (c >= 0x20 && c <= 0x7E)));
        assertTrue(qr.contains("FABIOLA/RODRIGUEZ ESPINOZA"));
        assertTrue(qr.contains("La Rosa Nautica Restaurant"));
    }
}