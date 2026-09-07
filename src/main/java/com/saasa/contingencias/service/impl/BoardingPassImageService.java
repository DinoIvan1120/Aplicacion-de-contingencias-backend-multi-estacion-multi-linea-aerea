package com.saasa.contingencias.service.impl;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.saasa.contingencias.domain.dto.response.BoardingPassScanResponse;
import com.saasa.contingencias.config.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Servicio para procesar imágenes de boarding pass y extraer códigos de barras.
 *
 * Utiliza la librería ZXing (Zebra Crossing) para detectar y decodificar códigos de barras
 * en imágenes subidas por el usuario. Soporta múltiples formatos:
 * - PDF417 (formato más común en boarding pass)
 * - QR Code
 * - Code 128
 * - Aztec
 * - Data Matrix
 *
 * Una vez extraído el código de barras, delega el parsing del formato IATA BCBP
 * al servicio BoardingPassServiceImpl existente.
 *
 * @author SAASA Development Team
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BoardingPassImageService {

    private final BoardingPassServiceImpl boardingPassService;

    /**
     * Procesa una imagen de boarding pass y extrae el código de barras IATA.
     *
     * Flujo:
     * 1. Valida que la imagen sea válida y no esté corrupta
     * 2. Lee la imagen usando ImageIO
     * 3. Detecta el código de barras usando ZXing
     * 4. Extrae el texto del código
     * 5. Delega el parsing IATA al servicio existente
     *
     * @param imagen Archivo de imagen (JPG, PNG, etc.) del boarding pass
     * @return BoardingPassScanResponse con datos extraídos (nombre, PNR, vuelo, etc.)
     * @throws BadRequestException si la imagen está vacía, corrupta, o no contiene código legible
     */
    public BoardingPassScanResponse procesarImagen(MultipartFile imagen) {

        // ═══════════════════════════════════════════════════════════════════════
        // Validaciones de entrada
        // ═══════════════════════════════════════════════════════════════════════

        if (imagen == null || imagen.isEmpty()) {
            throw new BadRequestException("La imagen está vacía");
        }

        String contentType = imagen.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BadRequestException(
                    "El archivo debe ser una imagen (JPG, PNG, WEBP, etc.)");
        }

        log.info("📸 Procesando imagen de boarding pass: {} ({} bytes)",
                imagen.getOriginalFilename(), imagen.getSize());

        try {
            // ═══════════════════════════════════════════════════════════════════════
            // Lectura de la imagen
            // ═══════════════════════════════════════════════════════════════════════

            BufferedImage bufferedImage = ImageIO.read(imagen.getInputStream());

            if (bufferedImage == null) {
                throw new BadRequestException(
                        "No se pudo leer la imagen. Verifica que el archivo no esté corrupto.");
            }

            log.info("✅ Imagen leída correctamente: {}x{} px",
                    bufferedImage.getWidth(), bufferedImage.getHeight());

            // ═══════════════════════════════════════════════════════════════════════
            // Detección de código de barras
            // ═══════════════════════════════════════════════════════════════════════

            String codigoBarras = detectarConMultiplesEstrategias(bufferedImage);

            if (codigoBarras == null || codigoBarras.isBlank()) {
                throw new BadRequestException(
                        "No se detectó ningún código de barras en la imagen. " +
                                "Consejos para mejorar la detección:\n" +
                                "• Asegúrate de que la foto sea clara y frontal\n" +
                                "• Evita sombras y reflejos\n" +
                                "• Aumenta la iluminación\n" +
                                "• Acerca más la cámara al código de barras");
            }

            log.info("✅ Código de barras detectado: {} caracteres",
                    codigoBarras.length());

            // ═══════════════════════════════════════════════════════════════════════
            // Parsing del código IATA BCBP
            // ═══════════════════════════════════════════════════════════════════════

            // Crear el request con el código extraído
            com.saasa.contingencias.domain.dto.request.BoardingPassScanRequest request =
                    new com.saasa.contingencias.domain.dto.request.BoardingPassScanRequest(codigoBarras);

            // Reutilizar el parser IATA existente
            return boardingPassService.escanearBoardingPass(request);

        } catch (IOException e) {
            log.error("❌ Error al leer imagen: {}", e.getMessage());
            throw new BadRequestException(
                    "Error al procesar la imagen: " + e.getMessage());
        }
    }

    /**
     * Detecta código de barras en una imagen usando ZXing.
     *
     * Intenta detectar múltiples formatos de códigos de barras comunes en boarding pass.
     * Usa hints de ZXing para mejorar la precisión de detección:
     * - TRY_HARDER: Algoritmo más exhaustivo (más lento pero más preciso)
     * - PURE_BARCODE: Asume que toda la imagen es un código de barras
     * - POSSIBLE_FORMATS: Lista de formatos a intentar
     *
     * @param imagen BufferedImage a procesar
     * @return Texto del código de barras detectado, o null si no se detecta nada
     */
    /**
     * Intenta detectar el barcode usando múltiples estrategias en orden de probabilidad.
     */
    private String detectarConMultiplesEstrategias(BufferedImage imagen) {

        List<BarcodeFormat> formatos = Arrays.asList(
                BarcodeFormat.PDF_417,
                BarcodeFormat.CODE_128,
                BarcodeFormat.QR_CODE,
                BarcodeFormat.AZTEC,
                BarcodeFormat.DATA_MATRIX
        );

        // ── Estrategia 1: Imagen original, HybridBinarizer, sin PURE_BARCODE ──
        log.info("🔍 Estrategia 1: imagen original + HybridBinarizer");
        String result = intentarLeer(imagen, false, false, formatos);
        if (result != null) return result;

        // ── Estrategia 2: Imagen original, GlobalHistogramBinarizer ──
        log.info("🔍 Estrategia 2: imagen original + GlobalHistogramBinarizer");
        result = intentarLeer(imagen, false, true, formatos);
        if (result != null) return result;

        // ── Estrategia 3: Imagen original, HybridBinarizer + PURE_BARCODE=true ──
        log.info("🔍 Estrategia 3: imagen original + PURE_BARCODE=true");
        result = intentarLeer(imagen, true, false, formatos);
        if (result != null) return result;

        // ── Estrategia 4: Imagen escalada al doble ──
        log.info("🔍 Estrategia 4: imagen escalada x2");
        BufferedImage escalada = escalarImagen(imagen, 2.0);
        result = intentarLeer(escalada, false, false, formatos);
        if (result != null) return result;

        result = intentarLeer(escalada, false, true, formatos);
        if (result != null) return result;

        // ── Estrategia 5: Imagen en escala de grises con alto contraste ──
        log.info("🔍 Estrategia 5: imagen en escala de grises + alto contraste");
        BufferedImage gris = convertirAGrisAltoContraste(imagen);
        result = intentarLeer(gris, false, false, formatos);
        if (result != null) return result;

        result = intentarLeer(gris, false, true, formatos);
        if (result != null) return result;

        // ── Estrategia 6: Crop mitad inferior (donde suele estar el barcode) ──
        log.info("🔍 Estrategia 6: crop mitad inferior");
        BufferedImage mitadInferior = cropMitadInferior(imagen);
        result = intentarLeer(mitadInferior, false, false, formatos);
        if (result != null) return result;

        result = intentarLeer(mitadInferior, true, false, formatos);
        if (result != null) return result;

        result = intentarLeer(mitadInferior, false, true, formatos);
        if (result != null) return result;

        // ── Estrategia 7: Crop mitad inferior escalada + grises ──
        log.info("🔍 Estrategia 7: crop inferior + escala x2 + grises");
        BufferedImage cropEscalado = escalarImagen(mitadInferior, 2.0);
        result = intentarLeer(cropEscalado, false, false, formatos);
        if (result != null) return result;

        result = intentarLeer(cropEscalado, false, true, formatos);
        if (result != null) return result;

        BufferedImage cropGris = convertirAGrisAltoContraste(cropEscalado);
        result = intentarLeer(cropGris, false, false, formatos);
        if (result != null) return result;

        result = intentarLeer(cropGris, true, false, formatos);
        if (result != null) return result;

        // ── Estrategia 8: crop franjas de 1/3 inferior ──
        log.info("🔍 Estrategia 8: crop tercio inferior");
        BufferedImage tercioInferior = cropTercioInferior(imagen);
        result = intentarLeer(tercioInferior, false, false, formatos);
        if (result != null) return result;

        result = intentarLeer(escalarImagen(tercioInferior, 2.0), false, false, formatos);
        if (result != null) return result;

        result = intentarLeer(convertirAGrisAltoContraste(tercioInferior), false, false, formatos);
        if (result != null) return result;

// ── Estrategia 9: imagen invertida (barras blancas sobre fondo negro) ──
        log.info("🔍 Estrategia 9: imagen invertida");
        BufferedImage invertida = invertirImagen(imagen);
        result = intentarLeer(invertida, false, false, formatos);
        if (result != null) return result;

        result = intentarLeer(invertida, false, true, formatos);
        if (result != null) return result;





        log.warn("⚠️ Ninguna estrategia detectó el código de barras");
        return null;
    }

    /**
     * Intenta leer un código de barras con la configuración especificada.
     */
    private String intentarLeer(BufferedImage imagen, boolean pureBarcode,
                                boolean useHistogram, List<BarcodeFormat> formatos) {
        try {
            Map<DecodeHintType, Object> hints = new HashMap<>();
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, formatos);
            if (pureBarcode) {
                hints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);
            }

            LuminanceSource source = new BufferedImageLuminanceSource(imagen);
            BinaryBitmap bitmap = new BinaryBitmap(
                    useHistogram
                            ? new GlobalHistogramBinarizer(source)
                            : new HybridBinarizer(source)
            );

            MultiFormatReader reader = new MultiFormatReader();
            Result result = reader.decode(bitmap, hints);

            log.info("✅ Código detectado! Formato: {}, Longitud: {} caracteres",
                    result.getBarcodeFormat(), result.getText().length());

            return result.getText();

        } catch (NotFoundException e) {
            return null;
        } catch (Exception e) {
            log.debug("Error en intento de lectura: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Escala la imagen por el factor dado usando interpolación bicúbica.
     */
    private BufferedImage escalarImagen(BufferedImage original, double factor) {
        int newWidth = (int) (original.getWidth() * factor);
        int newHeight = (int) (original.getHeight() * factor);

        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = scaled.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g2d.drawImage(original, 0, 0, newWidth, newHeight, null);
        g2d.dispose();

        return scaled;
    }

    /**
     * Convierte la imagen a escala de grises con alto contraste para mejorar
     * la legibilidad de códigos de barras en fotos con baja iluminación.
     */
    // ✅ Fix — usar umbral adaptativo por media de la imagen
    private BufferedImage convertirAGrisAltoContraste(BufferedImage original) {
        int w = original.getWidth();
        int h = original.getHeight();
        BufferedImage gris = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = gris.createGraphics();
        g2d.drawImage(original, 0, 0, null);
        g2d.dispose();

        // Calcular umbral adaptativo (media de luminosidad)
        long suma = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                suma += gris.getRGB(x, y) & 0xFF;
        int umbral = (int) (suma / ((long) w * h));

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = gris.getRGB(x, y) & 0xFF;
                int bw = pixel < umbral ? 0x000000 : 0xFFFFFF;
                gris.setRGB(x, y, bw);
            }
        }
        return gris;
    }

    /**
     * Recorta la mitad inferior de la imagen.
     * En la mayoría de los boarding pass, el barcode está en la parte inferior.
     */
    private BufferedImage cropMitadInferior(BufferedImage original) {
        int w = original.getWidth();
        int h = original.getHeight();
        int startY = h / 2;
        return original.getSubimage(0, startY, w, h - startY);
    }

    private BufferedImage cropTercioInferior(BufferedImage original) {
        int w = original.getWidth();
        int h = original.getHeight();
        int startY = (h * 2) / 3;
        return original.getSubimage(0, startY, w, h - startY);
    }

    private BufferedImage invertirImagen(BufferedImage original) {
        int w = original.getWidth();
        int h = original.getHeight();
        BufferedImage inv = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = original.getRGB(x, y);
                inv.setRGB(x, y, ~rgb & 0xFFFFFF);
            }
        }
        return inv;
    }
}
