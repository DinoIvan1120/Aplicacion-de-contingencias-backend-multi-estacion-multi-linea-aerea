package com.saasa.contingencias.util;

public class PnrValidator {
    private static final java.util.regex.Pattern PNR_PATTERN = java.util.regex.Pattern.compile("^[A-Z0-9]{6}$");

    public static boolean isValid(String pnr) {
        return pnr != null && PNR_PATTERN.matcher(pnr).matches();
    }
}
