package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.model.*;
import java.util.List;
import java.util.Map;

public interface IPdfGeneratorService {
    byte[] generarVoucher(Atencion atencion, List<ServicioAsignado> servicios);
    /**
     * NUEVO — Voucher grupal: genera UN solo PDF que cubre a varios
     * pasajeros que comparten PNR y correo de envío.
     */
    byte[] generarVoucherGrupal(List<Atencion> atenciones,
                                Map<Long, List<ServicioAsignado>> serviciosPorAtencion,
                                boolean serviciosCompartidos);

    /**
     * NUEVO — Invalida el caché en memoria del logo de una aerolínea.
     * Debe llamarse cada vez que se sube/reemplaza el logo en S3
     * (ver LineaAereaServiceImpl.subirLogo), para que el próximo
     * voucher generado descargue la versión nueva en vez de servir
     * bytes viejos desde el caché.
     */
    void invalidarCacheLogo(Long lineaAereaId);
}
