package com.saasa.contingencias.domain.dto.request;
import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request para asignar servicios a un pasajero.
 *
 * FLUJO:
 * 1. Frontend muestra recursos disponibles del registro diario
 * 2. Agente selecciona hotel, tipo de habitación, servicios adicionales
 * 3. Agente selecciona transporte y restaurante
 * 4. Backend asigna servicios y decrementa disponibilidad automáticamente
 */
public record ServicioAsignadoRequest(

        // ══════════════════════════════════════════════════════════════════════
        // Identificación del Recurso
        // ══════════════════════════════════════════════════════════════════════

        /**
         * ID del recurso habilitado para este vuelo (VueloRecurso)
         * Ejemplo: Hotel Costa del Sol Wyndham para el vuelo LA2015 de hoy
         */
        @NotNull(message = "El recurso del vuelo es obligatorio")
        Long vueloRecursoId,

        /**
         * Tipo de servicio: HOTEL, TRANSPORTE, RESTAURANTE
         * (Heredado del enum anterior, mantener compatibilidad)
         */
        @NotNull
        TipoDetalleEnum tipoDetalle,

        // ══════════════════════════════════════════════════════════════════════
        // Para HOTELES
        // ══════════════════════════════════════════════════════════════════════

        /**
         * Tipo de habitación asignada: "SIMPLE", "DOBLE", "MATRIMONIAL"
         * Solo aplica si tipoDetalle == HOTEL
         */
        String tipoHabitacion,

        /**
         * Servicios adicionales del hotel (checkboxes del frontend)
         */
        Boolean desayuno,
        Boolean almuerzo,
        Boolean cena,
        Boolean snack,

        // Dentro del record, después de `snack`:
        /** Fecha check-in al hotel */
        LocalDate fechaIngreso,

        /** Fecha check-out del hotel */
        LocalDate fechaSalida,

        // ══════════════════════════════════════════════════════════════════════
        // Para TRANSPORTE
        // ══════════════════════════════════════════════════════════════════════

        /**
         * Tipo de transporte: "INDIVIDUAL" o "GRUPAL"
         * Solo aplica si tipoDetalle == TRANSPORTE
         */
        String tipoTransporte,

        // ══════════════════════════════════════════════════════════════════════
        // Cantidad y Monto (Campos originales)
        // ══════════════════════════════════════════════════════════════════════

        /**
         * Cantidad de servicios (normalmente 1 para pasajero individual)
         */
        @Min(1)
        int cantidad

        // NOTA: montoUnitario se elimina - se calculará desde el proveedor en backend
) {

    /**
     * Validación custom: Si es HOTEL, debe tener tipoHabitacion
     */
    public ServicioAsignadoRequest {
        if (tipoDetalle == TipoDetalleEnum.HOTEL &&
                (tipoHabitacion == null || tipoHabitacion.isBlank())) {
            throw new IllegalArgumentException(
                    "Para servicios de HOTEL, el tipo de habitación es obligatorio");
        }
    }
}

