package com.saasa.contingencias.util;

import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * Utilidad para manejo consistente de fechas y horas en la aplicación.
 *
 * PROBLEMA RESUELTO:
 * Cuando se usa LocalDate.now() o LocalDateTime.now() sin especificar zona horaria,
 * Java usa la zona horaria del servidor (usualmente UTC).
 *
 * En Perú (Lima UTC-5), si son las 7PM (19:00):
 * - Hora en Lima: 18/Abril 19:00
 * - Hora en UTC:  19/Abril 00:00
 *
 * Esto causaba que los registros de la tarde no aparecieran porque
 * LocalDate.now() devolvía el día siguiente.
 *
 * SOLUCIÓN:
 * Usar siempre ZoneId de Lima para obtener fechas y horas locales.
 *
 * @author SAASA Development Team
 * @version 1.0.0
 */
public class DateTimeUtil {

    /**
     * Zona horaria de Lima, Perú (UTC-5, sin horario de verano)
     */
    public static final ZoneId LIMA_ZONE = ZoneId.of("America/Lima");

    /**
     * Obtiene la fecha actual en zona horaria de Lima.
     *
     * USO: En lugar de LocalDate.now(), usar DateTimeUtil.hoyEnLima()
     *
     * @return Fecha actual en Lima
     */
    public static LocalDate hoyEnLima() {
        return LocalDate.now(LIMA_ZONE);
    }

    /**
     * Obtiene la fecha y hora actual en zona horaria de Lima.
     *
     * USO: En lugar de LocalDateTime.now(), usar DateTimeUtil.ahoraEnLima()
     *
     * @return Fecha y hora actual en Lima
     */
    public static LocalDateTime ahoraEnLima() {
        return LocalDateTime.now(LIMA_ZONE);
    }

    /**
     * Obtiene ZonedDateTime actual en Lima.
     *
     * Útil cuando necesitas timestamp con zona horaria explícita.
     *
     * @return ZonedDateTime actual en Lima
     */
    public static ZonedDateTime ahoraZonedLima() {
        return ZonedDateTime.now(LIMA_ZONE);
    }

    /**
     * Convierte LocalDateTime a ZonedDateTime en zona de Lima.
     *
     * Útil para conversiones y comparaciones con timestamps de otras zonas.
     *
     * @param localDateTime Fecha/hora local
     * @return ZonedDateTime en zona de Lima
     */
    public static ZonedDateTime toZonedLima(LocalDateTime localDateTime) {
        return localDateTime.atZone(LIMA_ZONE);
    }

    /**
     * Convierte LocalDate a inicio del día en Lima.
     *
     * @param date Fecha
     * @return Inicio del día (00:00:00) en zona de Lima
     */
    public static ZonedDateTime inicioDelDiaEnLima(LocalDate date) {
        return date.atStartOfDay(LIMA_ZONE);
    }

    /**
     * Convierte LocalDate a fin del día en Lima.
     *
     * @param date Fecha
     * @return Fin del día (23:59:59.999999999) en zona de Lima
     */
    public static ZonedDateTime finDelDiaEnLima(LocalDate date) {
        return date.atTime(LocalTime.MAX).atZone(LIMA_ZONE);
    }

    /**
     * Verifica si una fecha es hoy en Lima.
     *
     * @param date Fecha a verificar
     * @return true si es hoy en Lima
     */
    public static boolean esHoyEnLima(LocalDate date) {
        return date.equals(hoyEnLima());
    }

    /**
     * Formatea fecha para display en formato DD/MM/YYYY
     *
     * @param date Fecha
     * @return String formateado
     */
    public static String formatearFecha(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    /**
     * Formatea fecha y hora para display en formato DD/MM/YYYY HH:mm
     *
     * @param dateTime Fecha y hora
     * @return String formateado
     */
    public static String formatearFechaHora(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    /**
     * Obtiene el inicio de la semana actual (lunes) en Lima.
     *
     * @return Fecha del lunes de la semana actual
     */
    public static LocalDate inicioSemanaEnLima() {
        LocalDate hoy = hoyEnLima();
        return hoy.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * Obtiene el fin de la semana actual (domingo) en Lima.
     *
     * @return Fecha del domingo de la semana actual
     */
    public static LocalDate finSemanaEnLima() {
        LocalDate hoy = hoyEnLima();
        return hoy.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    /**
     * Obtiene el primer día del mes actual en Lima.
     *
     * @return Primer día del mes
     */
    public static LocalDate inicioMesEnLima() {
        LocalDate hoy = hoyEnLima();
        return hoy.withDayOfMonth(1);
    }

    /**
     * Obtiene el último día del mes actual en Lima.
     *
     * @return Último día del mes
     */
    public static LocalDate finMesEnLima() {
        LocalDate hoy = hoyEnLima();
        return hoy.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
    }
}
