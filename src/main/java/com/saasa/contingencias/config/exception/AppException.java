package com.saasa.contingencias.config.exception;

public sealed class AppException extends RuntimeException
        permits AccesoDenegadoException, BadRequestException, PdfGenerationException, PnrDuplicadoException, ProveedorInactivoException, RecursoNoEncontradoException, TooManyRequestsException {

    public AppException(String message) { super(message); }
    public AppException(String message, Throwable cause) { super(message, cause); }
}
