package com.saasa.contingencias.util;

public final class AppConstants {
    private AppConstants() {}
    public static final String API_PREFIX = "/api/v1";
    public static final int MAX_EMAIL_RETRIES = 3;
    public static final int MAX_BATCH_PASAJEROS = 50;
    public static final int MAX_REPORTE_ROWS = 10000;
    public static final String CORRELATIVO_PREFIX = "SGC";
    public static final int MAX_TEXT_LENGTH = 5000; // 👈 añade esta línea
}
