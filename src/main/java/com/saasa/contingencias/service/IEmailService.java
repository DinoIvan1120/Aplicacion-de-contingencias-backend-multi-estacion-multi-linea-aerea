package com.saasa.contingencias.service;

import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;

import java.util.List;

public interface IEmailService {
    void enviarVoucher(String correoDestino, List<String> ccDestinos, String correlativo, byte[] pdfBytes, String nombrePasajero, IdiomaVoucherEnum idioma);



    /**
     * NUEVO — Igual que enviarVoucher (mismo adjunto ya generado por el
     * llamador, sin volver a generar el PDF ni a subirlo a S3), pero para
     * reenvíos disparados por una actualización de los datos del PDF: el
     * mensaje indica que los servicios fueron ACTUALIZADOS, no asignados
     * por primera vez.
     */
    void enviarVoucherActualizado(String correoDestino,List<String> ccDestinos, String correlativo, byte[] pdfBytes, String nombrePasajero,IdiomaVoucherEnum idioma);
    /**
     * NUEVO — Voucher grupal: envía UN solo correo con UN solo PDF adjunto
     * que cubre a varios pasajeros (mismo PNR/correo).
     */
    void enviarVoucherGrupal(String correoDestino, List<String> ccDestinos, String correlativoGrupo, byte[] pdfBytes, List<String> nombresPasajeros,IdiomaVoucherEnum idioma);
    /**
            * NUEVO — Reenvío grupal por actualización: mismo comportamiento que
     * enviarVoucherGrupal (UN solo correo, UN solo PDF grupal adjunto),
     * pero el mensaje indica que los servicios fueron ACTUALIZADOS, no
     * asignados por primera vez.
            */
    void reenviarVoucherGrupal(String correoDestino, List<String> ccDestinos, String correlativoGrupo, byte[] pdfBytes, List<String> nombresPasajeros,IdiomaVoucherEnum idioma);
    void reenviarVoucher(Long atencionId, String correoDestino,List<String> ccDestinos, Long usuarioId);
    void enviarCodigoVerificacion(String correo, String nombre, String codigo, int minutos);
    void enviarConfirmacionReset(String correo, String nombre,String nuevaPassword);

}
