package com.saasa.contingencias.service;

/**
 * NUEVO — Voucher en formato ticket (impresora térmica de rollo).
 * Es independiente del voucher A4: no sube nada a S3 ni envía correos.
 */
public interface IVoucherTicketService {

    /** Ancho de rollo de 3" (impresoras de 80 mm). */
    int ANCHO_80_MM = 80;
    /** Ancho de rollo de 4" (por ejemplo Zebra ZQ521). */
    int ANCHO_104_MM = 104;

    /**
     * PDF del ticket listo para imprimir.
     *
     * @param contenido     bytes del PDF
     * @param nombreArchivo nombre sugerido para la descarga
     * @param grupal        true si se generó el ticket GRUPAL
     * @param pasajeros     cantidad de pasajeros incluidos
     */
    record TicketPdf(byte[] contenido, String nombreArchivo, boolean grupal, int pasajeros) {}

    /**
     * Genera el ticket de la atención indicada. Si la atención pertenece a
     * un grupo (grupoId con 2 o más pasajeros) devuelve automáticamente el
     * ticket GRUPAL, igual que el reenvío del voucher A4; con cualquier id
     * del grupo se obtiene el mismo ticket.
     *
     * @param atencionId id de la atención (individual o de cualquier integrante del grupo)
     * @param anchoMm    80 o 104
     */
    TicketPdf generarTicket(Long atencionId, int anchoMm);
}