package com.saasa.contingencias.util;

public class IataCodigo {
    private static final java.util.regex.Pattern IATA_PATTERN = java.util.regex.Pattern.compile("^[A-Z]{3}$");

    public static boolean isValid(String code) {
        return code != null && IATA_PATTERN.matcher(code).matches();
    }
}
