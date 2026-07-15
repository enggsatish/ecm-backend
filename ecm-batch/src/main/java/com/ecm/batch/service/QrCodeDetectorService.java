package com.ecm.batch.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Detects and decodes QR codes from document images.
 * <p>
 * QR codes on batch documents may contain pre-encoded metadata such as
 * customer ID, category code, or case reference — enabling fast-track
 * classification without OCR/AI. Also understands the JSON payload eForms
 * embeds in generated form PDFs ({@code ecm/fk/v/fd/sid/pid/cid}).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QrCodeDetectorService {

    private final ObjectMapper objectMapper;

    /**
     * Attempt to detect and decode a QR code from file bytes.
     *
     * @param fileBytes raw file bytes (image or first page of PDF rendered to image)
     * @return decoded QR data as key-value pairs, or empty if no QR found
     */
    public Optional<Map<String, String>> detect(byte[] fileBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(fileBytes));
            if (image == null) {
                log.debug("Could not read image from file bytes — skipping QR detection");
                return Optional.empty();
            }

            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            Result result = new MultiFormatReader().decode(bitmap);
            String text = result.getText();

            log.info("QR code detected: {}", text);
            return Optional.of(parseQrText(text));

        } catch (NotFoundException e) {
            log.debug("No QR code found in document");
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Error during QR code detection: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parse QR text into key-value pairs.
     * Supports formats: JSON object (eForms-generated QR — {@code {"ecm":true,"fk":"...",...}}),
     * key1=value1;key2=value2, or plain text.
     */
    private Map<String, String> parseQrText(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> raw = objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {});
                Map<String, String> data = new HashMap<>();
                raw.forEach((k, v) -> { if (v != null) data.put(k, String.valueOf(v)); });
                return data;
            } catch (Exception e) {
                log.debug("QR text looked like JSON but failed to parse: {}", e.getMessage());
                // fall through to key=value / raw handling below
            }
        }

        Map<String, String> data = new HashMap<>();
        if (text.contains("=")) {
            String[] pairs = text.split("[;&]");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    data.put(kv[0].trim(), kv[1].trim());
                }
            }
        } else {
            data.put("raw", text);
        }
        return data;
    }
}
