package com.ecm.ocr.engine;

import com.ecm.ocr.properties.OcrProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;

/**
 * Tika 3.x OCR engine.
 *
 * API changes from Tika 2.x:
 *  1. TesseractOCRConfig.setTimeoutSeconds()  — unchanged, still valid
 *  2. TesseractOCRConfig.setSkipOCR()         — renamed to setSkipOcr() in 3.x
 *     (3.x reverted the 2.x capitalisation — back to camelCase)
 *  3. PDFBox compatibility — resolved automatically; Tika 3.x depends on PDFBox 3.x
 *     and no longer calls the removed PDDocument.load(InputStream, String, MemoryUsageSetting)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TikaOcrEngine implements OcrEngine {

    private final OcrProperties props;

    @Override
    public String extract(InputStream inputStream, String contentType) throws OcrException {
        try {
            AutoDetectParser parser  = new AutoDetectParser();
            // -1 = unlimited content; prevents truncation on large documents
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata          = new Metadata();

            TesseractOCRConfig tessConf = new TesseractOCRConfig();
            tessConf.setLanguage(props.getTesseractLanguages());
            tessConf.setTimeoutSeconds(120);

            if (!props.isTesseractEnabled()) {
                // Tika 3.x reverted the 2.x capitalisation change:
                //   Tika 2.x: setSkipOCR(true)   ← uppercase OCR
                //   Tika 3.x: setSkipOcr(true)   ← camelCase, back to original
                tessConf.setSkipOcr(true);
            }

            ParseContext context = new ParseContext();
            context.set(TesseractOCRConfig.class, tessConf);
            context.set(Parser.class, parser);  // needed for recursive parsing (e.g. zip, email)

            parser.parse(inputStream, handler, metadata, context);

            String text = handler.toString().strip();
            log.debug("Tika extracted {} chars, contentType={}", text.length(), contentType);
            return text;

        } catch (SAXException | IOException | TikaException e) {
            throw new OcrException("Tika extraction failed: " + e.getMessage(), e);
        }
        // TikaException is a checked exception in 2.x but unchecked in 3.x — no catch needed
    }
}