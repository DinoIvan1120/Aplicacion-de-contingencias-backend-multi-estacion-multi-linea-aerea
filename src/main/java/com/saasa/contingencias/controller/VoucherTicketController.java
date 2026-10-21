package com.saasa.contingencias.controller;

import com.saasa.contingencias.service.IVoucherTicketService;
import com.saasa.contingencias.service.IVoucherTicketService.TicketPdf;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * NUEVO — Voucher tipo ticket para impresoras térmicas de rollo.
 * Controller aparte para no tocar AtencionController.
 */
@RestController
@RequestMapping("/api/v1/atenciones")
@Tag(name = "Voucher ticket")
public class VoucherTicketController {

    private final IVoucherTicketService voucherTicketService;

    public VoucherTicketController(IVoucherTicketService voucherTicketService) {
        this.voucherTicketService = voucherTicketService;
    }

    @GetMapping("/{id}/voucher/ticket")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','LIDER_SAASA','AGENTE_SAASA')")
    @Operation(summary = "Genera el voucher en formato ticket (80 o 104 mm). "
            + "Si la atención es de un grupo, devuelve el ticket GRUPAL. No sube a S3 ni envía nada.")
    public ResponseEntity<byte[]> generarTicket(
            @PathVariable Long id,
            @RequestParam(defaultValue = "80") int ancho) {
        TicketPdf ticket = voucherTicketService.generarTicket(id, ancho);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + ticket.nombreArchivo() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .body(ticket.contenido());
    }
}