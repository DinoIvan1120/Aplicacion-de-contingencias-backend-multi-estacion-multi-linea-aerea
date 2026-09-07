package com.saasa.contingencias.domain.dto.response;

/**
 * Resumen de la capacidad ya comprometida hoy para un proveedor.
 *
 * Se devuelve como mapa { proveedorId → CapacidadComprometidaResponse }
 * desde GET /registros-diarios/comprometido-hoy
 *
 * Semántica por tipo de proveedor:
 *
 *   HOTEL:
 *     simplesComprometidos     → suma de habitaciones simples asignadas hoy
 *     doblesComprometidos      → suma de habitaciones dobles asignadas hoy
 *     matrimonialesComprometidos → suma de habitaciones matrimoniales asignadas hoy
 *     totalComprometido        → suma de las tres (calculado)
 *     capacidadTotalComprometida → null (no aplica para hotel)
 *
 *   TRANSPORTE / RESTAURANTE:
 *     simplesComprometidos / doblesComprometidos / matrimonialesComprometidos → null
 *     capacidadTotalComprometida → suma de capacidadTotal asignada hoy
 *     totalComprometido          → igual a capacidadTotalComprometida
 */
public record CapacidadComprometidaResponse(
        Long    proveedorId,
        String  proveedorNombre,
        String  proveedorTipo,

        // Campos hotel
        Integer simplesComprometidos,
        Integer doblesComprometidos,
        Integer matrimonialesComprometidos,

        // Campo transporte / restaurante
        Integer capacidadTotalComprometida
) {
    /** Total comprometido (para hotel = suma de 3 tipos; para otros = capacidadTotal). */
    public int totalComprometido() {
        if ("HOTEL".equals(proveedorTipo)) {
            return nvl(simplesComprometidos)
                    + nvl(doblesComprometidos)
                    + nvl(matrimonialesComprometidos);
        }
        return nvl(capacidadTotalComprometida);
    }

    private static int nvl(Integer v) { return v != null ? v : 0; }
}

