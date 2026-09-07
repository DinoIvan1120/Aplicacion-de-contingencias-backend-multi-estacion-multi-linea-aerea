package com.saasa.contingencias.domain.dto.request;
import com.saasa.contingencias.domain.enumeration.ContingenciaEnum;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
public record VueloRequest(
        @NotBlank String aerolinea,
        @NotBlank String codigoVuelo,
        @NotBlank @Size(min=3, max=3, message="Código IATA debe tener 3 letras") @Pattern(regexp="^[A-Z]{3}$", message="Código IATA inválido") String origen,
        @NotBlank @Size(min=3, max=3) @Pattern(regexp="^[A-Z]{3}$") String destino,
        @NotNull LocalDate fechaVuelo,
        @NotNull ContingenciaEnum tipoContingencia,
        String observaciones,
        Long estacionId,
        // Contexto de trabajo activo (topbar). Opcional en el body: si no viaja
        // aquí, se resuelve igual desde los headers X-Estacion-Id/X-Linea-Aerea-Id
        // (ver ContextoActivoHolder), que es como lo manda el frontend por defecto.
        Long lineaAereaId
) {}