package com.saasa.contingencias.config.security;

/**
 * Contexto de trabajo activo (estación+línea aérea) del request HTTP en
 * curso, tal como lo envía el selector del topbar del frontend en los
 * headers X-Estacion-Id / X-Linea-Aerea-Id de CADA petición (lectura o
 * escritura) — no solo al crear registros, que era el comportamiento
 * anterior.
 *
 * Es un ThreadLocal poblado por JwtFilter al inicio del request y
 * limpiado siempre al final (bloque finally), igual que el
 * SecurityContextHolder de Spring Security. Lo consume
 * EstacionContext#resolverContextoActivo(Long, Long) como fallback
 * cuando el controller no recibe estacionId/lineaAereaId explícitos en
 * query params o body.
 */
public final class ContextoActivoHolder {

    public static final String HEADER_ESTACION = "X-Estacion-Id";
    public static final String HEADER_LINEA_AEREA = "X-Linea-Aerea-Id";

    private static final ThreadLocal<Long> ESTACION_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> LINEA_AEREA_ID = new ThreadLocal<>();

    private ContextoActivoHolder() {}

    public static void set(Long estacionId, Long lineaAereaId) {
        ESTACION_ID.set(estacionId);
        LINEA_AEREA_ID.set(lineaAereaId);
    }

    public static Long getEstacionId() {
        return ESTACION_ID.get();
    }

    public static Long getLineaAereaId() {
        return LINEA_AEREA_ID.get();
    }

    public static void clear() {
        ESTACION_ID.remove();
        LINEA_AEREA_ID.remove();
    }
}
