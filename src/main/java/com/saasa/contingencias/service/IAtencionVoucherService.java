package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.dto.request.EnvioEmailRequest;
import com.saasa.contingencias.domain.dto.request.GenerarVoucherRequest;
import com.saasa.contingencias.domain.dto.request.VoucherGrupalRequest;
import com.saasa.contingencias.domain.dto.response.VoucherGrupalResponse;
import com.saasa.contingencias.domain.dto.response.VoucherResponse;

import java.util.Map;

public interface IAtencionVoucherService {

    VoucherResponse generarVoucherPdf(Long atencionId);
    VoucherResponse generarYEnviarVoucher(Long atencionId, GenerarVoucherRequest request, Long usuarioId);
    String generarYEnviarVoucherLegacy(Long atencionId, Long usuarioId);
    void reenviarPdf(Long atencionId, EnvioEmailRequest request, Long usuarioId);
    byte[] descargarPdf(Long atencionId);
    Map<String, String> urlFirmadaVoucher(Long atencionId);

    VoucherGrupalResponse generarVoucherGrupalPdf(VoucherGrupalRequest request);

    VoucherGrupalResponse generarYEnviarVoucherGrupal(VoucherGrupalRequest request, Long usuarioId);
}
