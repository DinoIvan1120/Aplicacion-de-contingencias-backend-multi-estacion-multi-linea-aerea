package com.saasa.contingencias.config.exception;

public non-sealed class PdfGenerationException extends AppException {
    public PdfGenerationException(String message) {
        super(message);
    }
    public PdfGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}