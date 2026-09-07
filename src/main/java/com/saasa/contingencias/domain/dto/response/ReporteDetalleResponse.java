package com.saasa.contingencias.domain.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;


/**
 * Response detallado de un voucher individual.
 *
 * Incluye toda la información necesaria para:
 * - Mostrar el detalle completo del voucher
 * - Editar servicios asignados
 * - Regenerar PDF
 */
public record ReporteDetalleResponse(
        // Datos de la atención
        Long atencionId,
        Long registroVueloDiarioId,
        String correlativo,
        String pnr,
        String codigoAutorizacion,
        String estado,

        // Datos del pasajero
        String nombrePasajero,
        String apellidoPasajero,
        String correoPasajero,

        // TWILIO — Teléfono del pasajero (puede ser null si no fue registrado)
        String telefonoPasajero,

        // Datos del vuelo
        Long vueloId,
        String codigoVuelo,
        String aerolinea,
        LocalDate fechaVuelo,
        String origen,
        String destino,

        // Datos del boarding pass
        String codigoBarras,
        LocalDate fechaEmision,
        String lugarEmision,

        // Servicios asignados
        ServicioDetalleResponse hotel,
        ServicioDetalleResponse transporte,
        ServicioDetalleResponse restaurante,

        // Totales
        BigDecimal totalGeneral,

        // Metadata
        String generadoPor,
        /**
         * NUEVO — Rol (RolEnum.name(): ADMINISTRADOR | LIDER_SAASA |
         * AGENTE_SAASA | LINEA_AEREA | PROVEEDOR) de quien generó el
         * voucher (atendidoPor). Null si atendidoPor no tiene rol asignado.
         */
        String rolGenerador,

        /**
         * NUEVO — Nombre completo del último usuario que actualizó esta
         * atención (servicios o datos del pasajero). Null si nunca fue
         * modificada tras su creación.
         */
        String actualizadoPor,

        /**
         * NUEVO — Rol de quien realizó la última actualización. Null si
         * nunca fue modificada.
         */
        String rolActualizador,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String pdfUrl,

        /**NUEVO - Voucher grupal: distinto de null cuando esta atención
         * forma parte de un grupo (mismo PNR/correo, un solo PDF/Voucher).
         */
        String grupoId,

        /**
         * NUEVO — Voucher grupal: nombres de TODOS los pasajeros incluidos
         * en el mismo voucher PDF (incluyendo el propio). Vacío/null si la
         * atención no pertenece a ningún grupo.
         */
        List<String> pasajerosGrupo,

        /**
         * NUEVO — Firma digital de conformidad del pasajero. Si la atención
         * pertenece a un voucher grupal, este valor es el mismo que el del
         * titular (así se aplicó al enviar el voucher grupal).
         */
        String firmaPasajero,
        Boolean firmaConforme,
        LocalDateTime firmaFecha,

        /**
         * NUEVO — Quién firmó: "PASAJERO" (firma real del pasajero, vista
         * individual) o "AGENTE_LOTE" (nombre de quien autorizó una carga
         * masiva). Null en registros previos a este cambio.
         */
        String origenFirma,

        /**
         * NUEVO — Rol real (ADMINISTRADOR | LIDER_SAASA | AGENTE_SAASA) de
         * quien autorizó la carga masiva, solo presente cuando
         * origenFirma = "AGENTE_LOTE". El frontend lo usa para mostrar
         * dinámicamente "Autorización del administrador/líder/agente
         * (carga masiva)" en vez de un texto fijo.
         */
        String origenFirmaRol,

        /**
         * NUEVO — Idioma del voucher PDF ("ES" o "EN"). Preferencia
         * persistida del pasajero; se usa cada vez que se genera/regenera
         * el PDF.
         */
        String idiomaVoucher,

        Boolean puedeEditarServicios,
        String correlativoTitularGrupo,
        String nombreTitularGrupo,     // NUEVO
        String apellidoTitularGrupo    // NUEVO
) {

    /**
     * Detalle de un servicio asignado (Hotel, Transporte o Restaurante)
     */
    public record ServicioDetalleResponse(
            Long servicioAsignadoId,
            Long vueloRecursoId,

            // Datos del proveedor
            Long proveedorId,
            String proveedorNombre,
            String proveedorTipo,  // HOTEL, TRANSPORTE, RESTAURANTE

            // Para HOTEL
            String tipoHabitacion,           // SIMPLE, DOBLE, MATRIMONIAL
            Integer cantidadHabitaciones,
            BigDecimal precioHabitacion,

            // Servicios de alimentación (pueden estar en HOTEL o RESTAURANTE)
            Boolean desayuno,
            Boolean almuerzo,
            Boolean cena,
            Boolean snack,
            BigDecimal precioDesayuno,
            BigDecimal precioAlmuerzo,
            BigDecimal precioCena,
            BigDecimal precioSnack,

            // Para TRANSPORTE
            String tipoTransporte,           // INDIVIDUAL, GRUPAL
            Integer cantidadPasajeros,

            // Totales
            BigDecimal subtotal,
            LocalDate fechaIngreso,   // ← AGREGAR
            LocalDate fechaSalida     // ← AGREGAR
    ) {}
}

