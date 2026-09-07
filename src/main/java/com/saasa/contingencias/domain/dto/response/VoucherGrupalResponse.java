package com.saasa.contingencias.domain.dto.response;

import java.util.List;

public record VoucherGrupalResponse(
        List<String> numerosVoucher,
        String pdfUrl,
        Boolean emailEnviado,
        String correoDestino
) {
    public static VoucherGrupalResponse soloGenerado(List<String> numerosVoucher, String pdfUrl) {
        return new VoucherGrupalResponse(numerosVoucher, pdfUrl, false, null);
    }

    public static VoucherGrupalResponse generadoYEnviado(List<String> numerosVoucher, String pdfUrl, String correo) {
        return new VoucherGrupalResponse(numerosVoucher, pdfUrl, true, correo);
    }
}
