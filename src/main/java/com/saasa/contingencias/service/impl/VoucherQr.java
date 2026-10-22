package com.saasa.contingencias.service.impl;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.saasa.contingencias.domain.enumeration.IdiomaVoucherEnum;
import com.saasa.contingencias.domain.enumeration.TipoDetalleEnum;
import com.saasa.contingencias.domain.model.Atencion;
import com.saasa.contingencias.domain.model.Proveedor;
import com.saasa.contingencias.domain.model.ServicioAsignado;
import com.saasa.contingencias.domain.model.Vuelo;
import com.saasa.contingencias.util.QrTexto;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Contenido y generación del QR de verificación del voucher, COMPARTIDO por
 * el voucher A4 (PdfGeneratorServiceImpl) y el ticket térmico
 * (TicketPdfRenderer). Así una misma atención lleva exactamente el mismo QR
 * en los dos formatos.
 *
 * Contenido: título y correlativo, pasajero(s), vuelo, PNR, lugar, cada
 * servicio con lo que incluye (igual que el ticket impreso) y el pie. No
 * lleva direcciones ni teléfonos de los proveedores: son datos de contacto,
 * no del voucher, y harían crecer el QR.
 *
 * Grupos de más de MAX_PASAJEROS_QR_DETALLE pasajeros: en lugar de la lista
 * de nombres va la cantidad y el rango de correlativos (un PNR compartido no
 * tiene límite; se han visto hasta 30), y si los servicios son por pasajero,
 * una línea que remite al voucher impreso.
 *
 * El texto se codifica en ASCII (sin tildes ni Ñ) y sin marca ECI UTF-8:
 * muchas apps de escaneo ignoran esa marca y leían "RODRÍGUEZ" como
 * "RODR虾UEZ".
 */
final class VoucherQr {

    /** Hasta esta cantidad de pasajeros el QR grupal lleva la lista de nombres. */
    static final int MAX_PASAJEROS_QR_DETALLE = 10;

    private static final DateTimeFormatter FMT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FMT_YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private VoucherQr() {
    }

    /** Una línea "Etiqueta: valor" de un bloque de servicio. */
    record Dato(String etiqueta, String valor) {
    }

    /** Un servicio (hotel, transporte o restaurante) tal como se imprime en el ticket. */
    record Bloque(String titulo, List<Dato> datos, Proveedor proveedor) {
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONTENIDO DEL QR
    // ═══════════════════════════════════════════════════════════════════════

    static String contenidoIndividual(Atencion atencion, List<ServicioAsignado> servicios,
                                      IdiomaVoucherEnum idioma) {
        List<String> l = new ArrayList<>();
        l.add(t(idioma, "VOUCHER DE SERVICIO ", "SERVICE VOUCHER ") + texto(atencion.getNumeroCorrelativo()));
        l.add(linea(t(idioma, "Pasajero:", "Passenger:"), nombreConBarra(atencion)));
        l.add(linea(t(idioma, "Vuelo:", "Flight:"), vueloInfo(atencion)));
        l.add(linea("PNR:", atencion.getPnr()));
        l.add(linea(t(idioma, "Lugar:", "Site:"), lugar(atencion)));
        agregarServicios(l, servicios, idioma);
        l.add(textoPie(atencion));
        return String.join("\n", l);
    }

    static String contenidoGrupal(List<Atencion> atenciones,
                                  Map<Long, List<ServicioAsignado>> serviciosPorAtencion,
                                  boolean serviciosCompartidos, IdiomaVoucherEnum idioma) {
        Atencion titular = atenciones.get(0);
        int total = atenciones.size();
        List<String> l = new ArrayList<>();
        l.add(t(idioma, "VOUCHER DE SERVICIO ", "SERVICE VOUCHER ") + texto(titular.getNumeroCorrelativo()));
        l.add(t(idioma, "GRUPAL - ", "GROUP - ") + total + t(idioma, " PASAJEROS", " PASSENGERS"));
        l.add(linea(t(idioma, "Vuelo:", "Flight:"), vueloInfo(titular)));
        l.add(linea("PNR:", titular.getPnr()));
        l.add(linea(t(idioma, "Lugar:", "Site:"), lugar(titular)));

        boolean resumido = total > MAX_PASAJEROS_QR_DETALLE;
        if (resumido) {
            Atencion ultimo = atenciones.get(total - 1);
            l.add(t(idioma, "PASAJEROS: ", "PASSENGERS: ") + total
                    + " (" + texto(titular.getNumeroCorrelativo())
                    + t(idioma, " a ", " to ") + texto(ultimo.getNumeroCorrelativo()) + ")");
        } else {
            l.add(t(idioma, "PASAJEROS (", "PASSENGERS (") + total + "):");
            int i = 1;
            for (Atencion a : atenciones) {
                l.add(i + ". " + nombreConBarra(a) + " " + texto(a.getNumeroCorrelativo()));
                i++;
            }
        }

        if (serviciosCompartidos) {
            agregarServicios(l, serviciosPorAtencion.getOrDefault(titular.getId(), List.of()), idioma);
        } else if (resumido) {
            l.add(t(idioma, "Servicios por pasajero: ver voucher impreso",
                    "Services per passenger: see printed voucher"));
        } else {
            int i = 1;
            for (Atencion a : atenciones) {
                l.add(t(idioma, "PASAJERO ", "PASSENGER ") + i + "/" + total + ": " + nombreConBarra(a));
                agregarServicios(l, serviciosPorAtencion.getOrDefault(a.getId(), List.of()), idioma);
                i++;
            }
        }
        l.add(textoPie(titular));
        return String.join("\n", l);
    }

    /** Los mismos bloques que imprime el ticket, sin dirección ni teléfono. */
    private static void agregarServicios(List<String> lineas, List<ServicioAsignado> servicios,
                                         IdiomaVoucherEnum idioma) {
        List<Bloque> bloques = bloques(servicios, idioma);
        if (bloques.isEmpty()) {
            lineas.add(sinServicios(idioma));
            return;
        }
        for (Bloque b : bloques) {
            lineas.add(b.titulo());
            for (Dato d : b.datos()) {
                lineas.add(linea(d.etiqueta(), d.valor()));
            }
        }
    }

    /** Si no hay valor se muestra "-", igual que en el ticket impreso. */
    private static String linea(String etiqueta, String valor) {
        return etiqueta + " " + (valor == null || valor.isBlank() ? "-" : valor);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // IMAGEN DEL QR
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * PNG del QR, nítido para impresión: se codifica primero a su tamaño
     * nativo (1 px por módulo) y luego a 8 px exactos por módulo, para que
     * al escalarlo no queden módulos borrosos o desiguales. El texto se pasa
     * a ASCII y no se usa CHARACTER_SET, así el QR no lleva marca ECI.
     */
    static byte[] png(String contenido) throws Exception {
        String texto = QrTexto.paraQr(contenido);
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 1);

        BitMatrix nativo = writer.encode(texto, BarcodeFormat.QR_CODE, 0, 0, hints);
        int lado = nativo.getWidth() * 8;
        BitMatrix matriz = writer.encode(texto, BarcodeFormat.QR_CODE, lado, lado, hints);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matriz, "PNG", out);
        return out.toByteArray();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BLOQUES DE SERVICIO (también los usa el ticket para imprimir)
    // ═══════════════════════════════════════════════════════════════════════

    static List<Bloque> bloques(List<ServicioAsignado> servicios, IdiomaVoucherEnum idioma) {
        List<Bloque> bloques = new ArrayList<>();
        if (servicios == null || servicios.isEmpty()) return bloques;

        Map<TipoDetalleEnum, List<ServicioAsignado>> porTipo = servicios.stream()
                .collect(Collectors.groupingBy(ServicioAsignado::getTipoDetalle));

        // ── HOTEL ──────────────────────────────────────────────────────────
        List<ServicioAsignado> hoteles = porTipo.get(TipoDetalleEnum.HOTEL);
        if (hoteles != null && !hoteles.isEmpty()) {
            ServicioAsignado hotel = hoteles.get(0);
            List<Dato> datos = new ArrayList<>();

            if (hotel.getFechaIngreso() != null || hotel.getFechaSalida() != null) {
                datos.add(new Dato("Check-in:", fecha(hotel.getFechaIngreso())));
                datos.add(new Dato("Check-out:", fecha(hotel.getFechaSalida())));
            }

            String habitaciones = hoteles.stream()
                    .map(s -> cantidad(s) + " " + traducirTipoHabitacion(
                            s.getTipoHabitacion() != null ? s.getTipoHabitacion() : "SIMPLE", idioma))
                    .collect(Collectors.joining(", "));
            datos.add(new Dato(t(idioma, "Hab.:", "Room(s):"), habitaciones));

            int des = 0, alm = 0, snk = 0, cen = 0;
            for (ServicioAsignado s : hoteles) {
                if (Boolean.TRUE.equals(s.getDesayuno())) des += cantidad(s);
                if (Boolean.TRUE.equals(s.getAlmuerzo())) alm += cantidad(s);
                if (Boolean.TRUE.equals(s.getSnack()))    snk += cantidad(s);
                if (Boolean.TRUE.equals(s.getCena()))     cen += cantidad(s);
            }
            String incluye = comidas(idioma, des, alm, snk, cen);
            if (!incluye.isEmpty()) datos.add(new Dato(t(idioma, "Incluye:", "Includes:"), incluye));

            bloques.add(new Bloque("HOTEL: " + nombreProveedor(hotel).toUpperCase(), datos, proveedor(hotel)));
        }

        // ── TRANSPORTE ─────────────────────────────────────────────────────
        List<ServicioAsignado> transportes = porTipo.get(TipoDetalleEnum.TRANSPORTE);
        if (transportes != null && !transportes.isEmpty()) {
            ServicioAsignado trans = transportes.get(0);
            List<Dato> datos = new ArrayList<>();
            datos.add(new Dato(t(idioma, "Pasajeros:", "Passengers:"), String.valueOf(cantidad(trans))));
            datos.add(new Dato(t(idioma, "Tipo:", "Type:"), traducirTipoTransporte(trans.getTipoTransporte(), idioma)));
            bloques.add(new Bloque(t(idioma, "TRANSPORTE: ", "TRANSPORTATION: ") + nombreProveedor(trans).toUpperCase(),
                    datos, proveedor(trans)));
        }

        // ── RESTAURANTE ────────────────────────────────────────────────────
        List<ServicioAsignado> restaurantes = porTipo.get(TipoDetalleEnum.RESTAURANTE);
        if (restaurantes != null && !restaurantes.isEmpty()) {
            ServicioAsignado rest = restaurantes.get(0);
            List<Dato> datos = new ArrayList<>();
            datos.add(new Dato("Pax:", String.valueOf(cantidad(rest))));

            int des = 0, alm = 0, cen = 0;
            for (ServicioAsignado s : restaurantes) {
                if (Boolean.TRUE.equals(s.getDesayuno())) des += cantidad(s);
                if (Boolean.TRUE.equals(s.getAlmuerzo())) alm += cantidad(s);
                if (Boolean.TRUE.equals(s.getCena()))     cen += cantidad(s);
            }
            String incluye = comidas(idioma, des, alm, 0, cen);
            if (!incluye.isEmpty()) datos.add(new Dato(t(idioma, "Incluye:", "Includes:"), incluye));

            bloques.add(new Bloque(t(idioma, "RESTAURANTE: ", "RESTAURANT: ") + nombreProveedor(rest).toUpperCase(),
                    datos, proveedor(rest)));
        }
        return bloques;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AUXILIARES (también los usa el ticket)
    // ═══════════════════════════════════════════════════════════════════════

    static String t(IdiomaVoucherEnum idioma, String es, String en) {
        return idioma == IdiomaVoucherEnum.EN ? en : es;
    }

    static String nombreConBarra(Atencion a) {
        return (texto(a.getNombre()) + "/" + texto(a.getApellido())).toUpperCase();
    }

    static String vueloInfo(Atencion a) {
        String info = a.getVuelo() != null ? texto(a.getVuelo().getCodigoVuelo()) : "";
        if (a.getFechaEmision() != null) {
            info += "/" + a.getFechaEmision().format(FMT_YYMMDD);
        }
        return info;
    }

    static String lugar(Atencion a) {
        return a.getLugarEmision() != null ? a.getLugarEmision() : "LIM";
    }

    static String textoPie(Atencion a) {
        Vuelo vuelo = a.getVuelo();
        String aerolinea = vuelo != null && vuelo.getAerolinea() != null ? vuelo.getAerolinea() : "PLUS ULTRA";
        return aerolinea + " - " + fechaPie(a);
    }

    static String sinServicios(IdiomaVoucherEnum idioma) {
        return t(idioma, "Sin servicios asignados", "No services assigned");
    }

    static String texto(String s) {
        return s != null ? s : "";
    }

    private static String fechaPie(Atencion a) {
        if (a.getFechaEmision() != null) return a.getFechaEmision().format(FMT_DATE);
        if (a.getCreatedAt() != null) return a.getCreatedAt().toLocalDate().format(FMT_DATE);
        return LocalDate.now().format(FMT_DATE);
    }

    private static String fecha(LocalDate fecha) {
        return fecha != null ? fecha.format(FMT_DATE) : "—";
    }

    private static String comidas(IdiomaVoucherEnum idioma, int desayunos, int almuerzos, int snacks, int cenas) {
        List<String> partes = new ArrayList<>();
        if (desayunos > 0) partes.add(desayunos + " " + (desayunos == 1 ? t(idioma, "desayuno", "breakfast") : t(idioma, "desayunos", "breakfasts")));
        if (almuerzos > 0) partes.add(almuerzos + " " + (almuerzos == 1 ? t(idioma, "almuerzo", "lunch") : t(idioma, "almuerzos", "lunches")));
        if (snacks > 0)    partes.add(snacks + " " + (snacks == 1 ? "snack" : "snacks"));
        if (cenas > 0)     partes.add(cenas + " " + (cenas == 1 ? t(idioma, "cena", "dinner") : t(idioma, "cenas", "dinners")));
        return String.join(", ", partes);
    }

    private static int cantidad(ServicioAsignado s) {
        return s.getCantidad() != null ? s.getCantidad() : 1;
    }

    private static Proveedor proveedor(ServicioAsignado s) {
        return s.getVueloRecurso() != null ? s.getVueloRecurso().getProveedor() : null;
    }

    private static String nombreProveedor(ServicioAsignado s) {
        Proveedor p = proveedor(s);
        return p != null && p.getNombre() != null ? p.getNombre() : "PROVEEDOR NO ESPECIFICADO";
    }

    private static String traducirTipoTransporte(String tipoTransporte, IdiomaVoucherEnum idioma) {
        if (tipoTransporte == null) return t(idioma, "AEROPUERTO - DOMICILIO", "AIRPORT - HOME");
        return switch (tipoTransporte.toUpperCase()) {
            case "INDIVIDUAL" -> t(idioma, "AEROPUERTO - DOMICILIO", "AIRPORT - HOME");
            case "GRUPAL" -> t(idioma, "DOMICILIO - AEROPUERTO", "HOME - AIRPORT");
            case "AMBOS" -> t(idioma,
                    "AEROPUERTO - DOMICILIO Y DOMICILIO - AEROPUERTO",
                    "AIRPORT - HOME AND HOME - AIRPORT");
            default -> tipoTransporte;
        };
    }

    private static String traducirTipoHabitacion(String tipoHabitacion, IdiomaVoucherEnum idioma) {
        if (tipoHabitacion == null) return t(idioma, "SIMPLE", "SINGLE");
        return switch (tipoHabitacion.toUpperCase()) {
            case "SIMPLE" -> t(idioma, "SIMPLE", "SINGLE");
            case "DOBLE" -> t(idioma, "DOBLE", "DOUBLE");
            case "MATRIMONIAL" -> t(idioma, "MATRIMONIAL", "MATRIMONIAL (DOUBLE BED)");
            default -> tipoHabitacion;
        };
    }
}