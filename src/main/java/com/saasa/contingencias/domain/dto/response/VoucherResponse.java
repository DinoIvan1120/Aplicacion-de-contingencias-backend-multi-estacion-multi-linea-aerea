package com.saasa.contingencias.domain.dto.response;

/**
 * Response con información del voucher generado.
 *
 * @param numeroVoucher Número correlativo del voucher
 * @param pdfUrl URL del PDF en S3
 * @param emailEnviado Si el email fue enviado (solo para generar-y-enviar)
 * @param correoDestino Email destino (solo si fue enviado)
 */
public record VoucherResponse(
        String numeroVoucher,
        String pdfUrl,
        Boolean emailEnviado,
        String correoDestino
) {
    /**
     * Constructor para voucher solo generado (no enviado).
     */
    public static VoucherResponse soloGenerado(String numeroVoucher, String pdfUrl) {
        return new VoucherResponse(numeroVoucher, pdfUrl, false, null);
    }

    /**
     * Constructor para voucher generado y enviado.
     */
    public static VoucherResponse generadoYEnviado(String numeroVoucher, String pdfUrl, String correo) {
        return new VoucherResponse(numeroVoucher, pdfUrl, true, correo);
    }
}

