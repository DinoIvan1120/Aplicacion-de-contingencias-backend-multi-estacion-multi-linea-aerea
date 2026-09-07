package com.saasa.contingencias.service.impl;


import com.saasa.contingencias.config.exception.BadRequestException;
import com.saasa.contingencias.domain.dto.request.BoardingPassScanRequest;
import com.saasa.contingencias.domain.dto.response.BoardingPassScanResponse;
import com.saasa.contingencias.service.IBoardingPassService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Implementación del servicio de decodificación de boarding pass.
 *
 * Formato IATA BCBP (Bar Coded Boarding Pass):
 * Ejemplo: M1INFANTESILVA/DOMINIK    EWSVFKRLIMLIMPUAL0302C009 31F 028 >5180WM1 0000
 *
 * Estructura:
 * M - Format Code
 * 1 - Number of legs
 * INFANTESILVA/DOMINIK - Passenger name (max 20 chars)
 * E - Electronic ticket indicator
 * WSVFKR - Booking reference (PNR)
 * LIM - From city
 * LIM - To city
 * PUAL - Operating carrier designator
 * 0302 - Flight number
 * C - Class of service
 * 009 - Julian date
 * 31F - Seat
 * 028 - Sequence number
 */
@Slf4j
@Service
public class BoardingPassServiceImpl implements IBoardingPassService {

    @Override
    public BoardingPassScanResponse escanearBoardingPass(BoardingPassScanRequest request) {
        log.info("Escaneando boarding pass");

        String barcode = request.codigoBarras().trim();

        if (barcode.length() < 60) {
            throw new BadRequestException("Código de barras inválido: longitud insuficiente");
        }

        try {
            // Validar formato
            if (!barcode.startsWith("M")) {
                throw new BadRequestException("Código de barras no es formato IATA BCBP");
            }

            int pos = 2; // Saltar M y número de legs

            // Extraer nombre (30 caracteres)
            String nombreCompleto = barcode.substring(pos, pos + 20).trim();
            pos += 20;

            // El estándar IATA BCBP M1 limita el nombre a 20 caracteres.
            // Si el campo viene lleno (sin espacio de relleno al final),
            // es señal de que el nombre real fue truncado por la aerolínea
            // al emitir el boarding pass y NO se puede recuperar del barcode.
            boolean nombreTruncado = nombreCompleto.length() == 20
                    && !barcode.substring(pos - 20, pos).endsWith(" ");

            while (pos < barcode.length() && barcode.charAt(pos) == ' ') {
                pos++;
            }

            // Saltar electronic ticket indicator (1 char)
            pos += 1;

            String pnrRaw = barcode.substring(pos, pos + 7);
            String pnr = pnrRaw.trim().length() >= 6
                    ? pnrRaw.substring(0, 6).trim()   // 6 chars estándar
                    : pnrRaw.trim();

            // Extraer PNR (6 caracteres)
            //String pnr = barcode.substring(pos, pos + 7).trim();
            pos += 7;

            // Extraer origen (3 caracteres)
            String origen = barcode.substring(pos, pos + 3).trim();
            pos += 3;

            // Extraer destino (3 caracteres)
            String destino = barcode.substring(pos, pos + 3).trim();
            pos += 3;

            // Extraer carrier (3 caracteres) + flight number (4 caracteres)
            String carrier = barcode.substring(pos, pos + 3).trim();
            pos += 3;
            String flightNumber = barcode.substring(pos, pos + 4).trim();
            String codigoVuelo = carrier + flightNumber;
            pos += 4;

            // Extraer clase (1 carácter)
            String clase = barcode.substring(pos, pos + 1).trim();
            pos += 1;

            // Extraer fecha Julian (3 caracteres) - convertir a fecha legible
            String julianDate = barcode.substring(pos, pos + 3).trim();
            pos += 3;

            // Extraer asiento (4 caracteres)
            String asiento = barcode.substring(pos, pos + 4).trim();
            pos += 4;

            // La hora de abordaje puede estar después del ">" si existe
            String horaAbordaje = "";
            if (barcode.contains(">")) {
                int abordajePos = barcode.indexOf(">") + 5;
                if (barcode.length() >= abordajePos + 4) {
                    String rawTime = barcode.substring(abordajePos, abordajePos + 4);
                    // Formato HHMM → HH:MM
                    if (rawTime.matches("\\d{4}")) {
                        horaAbordaje = rawTime.substring(0, 2) + ":" + rawTime.substring(2, 4);
                    }
                }
            }

            log.info("Boarding pass decodificado - PNR: {}, Vuelo: {}, Pasajero: {}",
                    pnr, codigoVuelo, nombreCompleto);

            return new BoardingPassScanResponse(
                    nombreCompleto,
                    pnr,
                    codigoVuelo,
                    origen,
                    destino,
                    clase,
                    asiento,
                    julianDate, // Enviar fecha Julian raw, el frontend puede convertirla si es necesario
                    horaAbordaje,
                    nombreTruncado
            );

        } catch (StringIndexOutOfBoundsException e) {
            log.error("Error al decodificar boarding pass", e);
            throw new BadRequestException("Código de barras inválido: formato incorrecto");
        }
    }
}