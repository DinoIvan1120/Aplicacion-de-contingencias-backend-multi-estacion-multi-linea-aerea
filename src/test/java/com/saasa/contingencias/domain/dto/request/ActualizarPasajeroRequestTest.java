package com.saasa.contingencias.domain.dto.request;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActualizarPasajeroRequestTest {

    @Test
    void constructor_todosLosCamposNulos_lanzaIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                new ActualizarPasajeroRequest(null, null, null, null, null,null));
    }

    @Test
    void constructor_conAlMenosUnCampo_noLanzaExcepcion() {
        assertDoesNotThrow(() ->
                new ActualizarPasajeroRequest(null, null, null, "+51987654321", null,null));
    }

    @Test
    void constructor_soloConPnr_noLanzaExcepcion() {
        assertDoesNotThrow(() ->
                new ActualizarPasajeroRequest(null, null, null, null, "ABC123",null));
    }

    // NUEVO — idioma del voucher (ES/EN)
    @Test
    void constructor_soloConIdiomaVoucher_noLanzaExcepcion() {
        assertDoesNotThrow(() ->
                new ActualizarPasajeroRequest(null, null, null, null, null, "EN"));
    }
}
