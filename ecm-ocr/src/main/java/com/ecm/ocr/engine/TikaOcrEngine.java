package com.ecm.ocr.engine;

import com.ecm.ocr.properties.OcrProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.sax.BodyContentHandler;

import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Tika-based embedded text extractor for Office documents and native PDFs.
 *
 * <p><b>NOT an OCR engine.</b> This extracts text that is already embedded in
 * the document (PDF text layer, Word/Excel/HTML content). OCR for scanned
 * documents is handled by the pipeline engines (GLM-OCR, Azure, RapidOCR).</p>
 *
 * <p>Used by OcrPipelineService to:</p>
 * <ul>
 *   <li>Detect if a PDF is native (has text) or scanned (needs page rendering)</li>
 *   <li>Extract text from Office documents (Word, Excel, etc.) — no OCR engine needed</li>
 * </ul>
 *
 * <p>Tika's internal Tesseract subprocess is always disabled (setSkipOcr=true).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TikaOcrEngine implements OcrEngine {

    private final OcrProperties props;

    @Override
    public String extract(InputStream inputStream, String contentType) throws OcrException {
        return extract(inputStream, contentType, null);
    }

    /**
     * Extracts embedded text from a document using Tika.
     * Does NOT perform OCR — only reads text already present in the file.
     */
    @Override
    public String extract(InputStream inputStream, String contentType, Object documentId) throws OcrException {
        try {
            AutoDetectParser   parser  = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata           metadata = new Metadata();

            TesseractOCRConfig tessConf = new TesseractOCRConfig();
            tessConf.setLanguage(props.getTikaLanguages());
            tessConf.setTimeoutSeconds(120);
            tessConf.setSkipOcr(true); // Always skip — OCR handled by pipeline engines

            ParseContext context = new ParseContext();
            context.set(TesseractOCRConfig.class, tessConf);
            context.set(Parser.class, parser);

            parser.parse(inputStream, handler, metadata, context);

            String text = handler.toString().strip();
            log.debug("Tika extracted {} chars | type={} docId={}", text.length(), contentType, documentId);
            return text;

        } catch (Exception e) {
            throw new OcrException("Tika extraction failed: " + e.getMessage(), e);
        }
    }
}
