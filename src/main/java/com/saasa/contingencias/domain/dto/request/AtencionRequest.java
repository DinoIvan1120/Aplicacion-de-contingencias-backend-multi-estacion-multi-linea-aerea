package com.saasa.contingencias.domain.dto.request;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

public record AtencionRequest(
    @NotBlank String nombre,
    @NotBlank String apellido,
    @NotBlank @Size(min=6, max=6, message="PNR debe tener 6 caracteres") @Pattern(regexp="^[A-Z0-9]{6}$", message="PNR alfanumérico en mayúsculas") String pnr,
    @NotBlank @Email String correo,

    /**
     * TWILIO — Teléfono del pasajero para WhatsApp.
     * Formato E.164. Ej: "+51987654321"
     * Opcional: si no se provee, el envío WhatsApp se omite automáticamente.
     */
    @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "Teléfono debe estar en formato E.164. Ej: +51987654321")
    String telefono,

    @NotNull Long vueloId,
    // ══════════════════════════════════════════════════════════════════════
    // NUEVO: Conexión con registro diario
    // ══════════════════════════════════════════════════════════════════════

    /**
     * ID del registro diario del vuelo.
     * Esto asegura que el pasajero se asocie con el vuelo específico registrado
     * para HOY por el líder, no solo con el vuelo del itinerario.
     */
    @NotNull(message = "El registro diario es obligatorio")
    Long registroVueloDiarioId,

    // ══════════════════════════════════════════════════════════════════════
    // Campos del Boarding Pass (opcionales)
    // ══════════════════════════════════════════════════════════════════════

    String codigoBarras,
    LocalDate fechaEmision,
    String lugarEmision,

    /**
     * Nuevo - Voucher grupal: identificador de correlación (UUID generado en el frontend)
     * compartido por todos los pasajeros de una misma reserva
     * (mismo PNR/correo). Opcional: null para registros completamente
     * independientes.
     */
    String grupoId,

    // ══════════════════════════════════════════════════════════════════════
    // NUEVO: Conformidad / firma digital del pasajero
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Firma digital de conformidad del pasajero.
     * NUEVO — ahora es la imagen PNG (data URL en base64) dibujada por el
     * pasajero en el canvas de firma del modal de confirmación del agente,
     * en vez del nombre tecleado del flujo anterior. Opcional a nivel de
     * validación de request (se exige a nivel de flujo en el frontend antes
     * de habilitar el botón "Enviar"), pero si viene se persiste junto con
     * la fecha/hora de firma. El límite generoso de caracteres solo evita
     * payloads desproporcionados; una firma dibujada normal ocupa muchísimo
     * menos.
     */
    @Size(max = 2_000_000, message = "La firma excede el tamaño máximo permitido")
    String firmaPasajero

) {}
