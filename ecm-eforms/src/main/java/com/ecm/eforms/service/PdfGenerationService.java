package com.ecm.eforms.service;

import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.model.entity.FormSubmission;
import com.ecm.eforms.model.schema.FieldType;
import com.ecm.eforms.model.schema.FormField;
import com.ecm.eforms.model.schema.FormSchema;
import com.ecm.eforms.model.schema.FormSection;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * PDF Generation Service
 * ══════════════════════
 *
 * Generates a schema-driven PDF from a FormSubmission. The layout is derived
 * from the formSchemaSnapshot captured at submit time, so sections, paragraph
 * text, section headers, dividers, and input fields all appear in their
 * designed order.
 *
 * ── Bug history ──────────────────────────────────────────────────────────────
 *
 * Bug 1 — PARAGRAPH / SECTION_HEADER text missing from PDF:
 *   Root cause: the original code only iterated submissionData (user-entered
 *   key→value pairs). The frontend renderer explicitly excludes PARAGRAPH,
 *   SECTION_HEADER, and DIVIDER from submissionData because they are display-
 *   only fields whose text lives in field.label, not in a submitted value.
 *   Fix: walk formSchemaSnapshot sections + fields in order and render each
 *   field type appropriately, pulling user values from submissionData only for
 *   actual input fields.
 *
 * Bug 2 — Long text overflows the right margin silently:
 *   Root cause: writeLine() wrote the entire string as a single PDF text run
 *   with no word-wrap. Text overflowed the printable area invisibly.
 *   Fix: writeWrappedText() breaks text into words and fills lines up to
 *   maxWidth before starting a new line.
 *
 * Bug 3 — Page overflow cuts off content silently:
 *   Root cause: `if (yPos < MARGIN + LINE_HEIGHT) break` stopped writing when
 *   the first page was full. No new page was added.
 *   Fix: the PageContext helper tracks yPos across pages. When yPos drops
 *   below the bottom margin, it closes the current PDPageContentStream, adds a
 *   new PDPage, opens a fresh stream, and resets yPos to the top.
 *
 * Bug 4 — Divider renders as "?????" in the PDF:
 *   Root cause: "─────" (U+2500 BOX DRAWING) was sanitised to "?" by the
 *   ASCII-only character filter. PDType1Font (Helvetica) uses WinAnsiEncoding
 *   which covers U+0020–U+00FF but not U+2500.
 *   Fix: drawHRule() uses PDPageContentStream line-drawing (moveTo/lineTo) to
 *   draw a real horizontal rule instead of a text character.
 *
 * Bug 5 — Accented characters in field values become "?":
 *   Root cause: the sanitiser `[^\x20-\x7E]` only allowed 7-bit ASCII.
 *   WinAnsiEncoding covers U+0020–U+00FF (Latin-1 Supplement).
 *   Fix: sanitiser widened to `[^\x20-\xFF]`.
 *
 * Apache PDFBox 3.x API is used (not iText — avoids AGPL licensing concerns).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PdfGenerationService {

    private final ObjectMapper objectMapper;

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final float MARGIN        = 50f;
    private static final float PAGE_WIDTH    = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT   = PDRectangle.A4.getHeight();
    private static final float PRINTABLE_W   = PAGE_WIDTH - 2 * MARGIN;   // ~495 pt
    private static final float TOP_Y         = PAGE_HEIGHT - MARGIN;       // first line y
    private static final float BOTTOM_Y      = MARGIN + 20f;               // page-break trigger

    private static final float LINE_HEIGHT_NORMAL  = 15f;
    private static final float LINE_HEIGHT_PARA    = 14f;   // tighter for paragraph body text
    private static final float LINE_HEIGHT_HEADING = 20f;
    private static final float FONT_SIZE_TITLE     = 16f;
    private static final float FONT_SIZE_HEADING   = 12f;
    private static final float FONT_SIZE_LABEL     = 9f;
    private static final float FONT_SIZE_VALUE      = 11f;
    private static final float FONT_SIZE_PARA      = 10f;
    private static final float FONT_SIZE_META      = 9f;
    private static final float LABEL_COL_WIDTH    = 140f;       // inline label column width
    private static final float QR_SIZE            = 80f;        // QR code size in points

    // ── Public API ────────────────────────────────────────────────────────────

    public byte[] generate(FormSubmission submission) {
        log.info("Generating PDF for submission={}, formKey={}",
                submission.getId(), submission.getFormKey());
        try {
            byte[] pdf = buildPdf(submission);
            log.info("PDF generated: {} bytes for submission={}", pdf.length, submission.getId());
            return pdf;
        } catch (IOException e) {
            log.error("PDF generation failed for submission={}: {}", submission.getId(), e.getMessage(), e);
            throw new PdfGenerationException("Failed to generate PDF for " + submission.getId(), e);
        }
    }

    /**
     * Blank print — same layout/QR mechanics as {@link #generate(FormSubmission)} but
     * with no submission data (empty fields) and no submitter/party identity in the QR
     * payload (sid/pid omitted — there's nothing to identify yet). Used for branch
     * walk-in scenarios: print blank, fill by hand, scan back in — the QR still lets
     * the scan resolve the form/category, just not the customer.
     */
    public byte[] generateBlank(FormDefinition definition) {
        log.info("Generating blank PDF for formKey={}, formDefinitionId={}",
                definition.getFormKey(), definition.getId());
        try {
            byte[] pdf = buildBlankPdf(definition);
            log.info("Blank PDF generated: {} bytes for formKey={}", pdf.length, definition.getFormKey());
            return pdf;
        } catch (IOException e) {
            log.error("Blank PDF generation failed for formKey={}: {}", definition.getFormKey(), e.getMessage(), e);
            throw new PdfGenerationException("Failed to generate blank PDF for " + definition.getFormKey(), e);
        }
    }

    // ── Core builder ──────────────────────────────────────────────────────────

    private byte[] buildPdf(FormSubmission submission) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDType1Font fontBold   = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontNormal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font fontOblique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

            PageContext ctx = new PageContext(doc, fontBold, fontNormal);

            // ── Title block ───────────────────────────────────────────
            String formTitle = resolveFormTitle(submission);
            ctx.writeText(fontBold, FONT_SIZE_TITLE, formTitle);
            ctx.moveDown(6);

            // ── Submission meta ───────────────────────────────────────
            String submittedAt = submission.getSubmittedAt() != null
                    ? submission.getSubmittedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"))
                    : "Draft";
            String name = submission.getSubmittedByName() != null
                    ? submission.getSubmittedByName() : submission.getSubmittedBy();

            ctx.writeText(fontNormal, FONT_SIZE_META, "Submitted by: " + safe(name)
                    + "   |   " + submittedAt
                    + "   |   Ref: " + submission.getId().toString().substring(0, 8).toUpperCase());
            ctx.moveDown(4);
            ctx.drawHRule();
            ctx.moveDown(8);

            // ── Schema-driven field rendering ─────────────────────────
            //
            // Walk formSchemaSnapshot in schema order so sections, paragraphs,
            // headers, dividers, and input fields all appear in the designed layout.
            //
            // formSchemaSnapshot is captured at submit time — it will never be null
            // for a submitted form (FormSubmissionService sets it). Guard for safety.
            FormSchema schema = submission.getFormSchemaSnapshot();
            Map<String, Object> data = submission.getSubmissionData();

            // Default to inline (label beside value) — matches frontend FormRenderer behavior.
            // Only use stacked layout if explicitly set to "stacked".
            boolean inline = schema == null
                    || schema.getLabelPosition() == null
                    || "inline".equals(schema.getLabelPosition());

            if (schema != null && schema.getSections() != null) {
                for (FormSection section : schema.getSections()) {
                    renderSection(ctx, section, data, fontBold, fontNormal, fontOblique, inline);
                }
            } else {
                // Fallback: schema snapshot unavailable — dump raw submissionData map
                log.warn("formSchemaSnapshot is null for submission={} — falling back to raw data dump",
                        submission.getId());
                renderRawDataFallback(ctx, data, fontBold, fontNormal);
            }

            // ── QR Code ──────────────────────────────────────────────
            try {
                byte[] qrPng = generateQrCode(submission);
                if (qrPng != null) {
                    ctx.moveDown(12);
                    ctx.drawHRule();
                    ctx.moveDown(6);
                    // Ensure room for QR + label
                    if (ctx.getY() < BOTTOM_Y + QR_SIZE + 20) {
                        ctx.newPage();
                    }
                    PDImageXObject qrImage = PDImageXObject.createFromByteArray(doc, qrPng, "qr.png");
                    // Draw QR at bottom-right of current area
                    float qrX = PAGE_WIDTH - MARGIN - QR_SIZE;
                    float qrY = ctx.getY() - QR_SIZE;
                    ctx.getStream().drawImage(qrImage, qrX, qrY, QR_SIZE, QR_SIZE);
                    ctx.writeText(fontNormal, FONT_SIZE_META, "ECM Form QR — scan to link this document");
                    ctx.moveDown(QR_SIZE - FONT_SIZE_META);
                }
            } catch (Exception e) {
                log.warn("QR code generation failed for submission={}: {}", submission.getId(), e.getMessage());
                // Non-fatal — continue without QR
            }

            // Signature block is now designer-driven: use SIGNATURE/INITIALS field types
            // in the form schema. The PDF renders anchor markers that DocuSign detects.

            ctx.close();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private byte[] buildBlankPdf(FormDefinition definition) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDType1Font fontBold   = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontNormal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font fontOblique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

            PageContext ctx = new PageContext(doc, fontBold, fontNormal);

            // ── Title block ───────────────────────────────────────────
            String formTitle = safe(definition.getFormKey() != null
                    ? definition.getFormKey().replace("-", " ").replace("_", " ")
                    : "Form");
            ctx.writeText(fontBold, FONT_SIZE_TITLE, formTitle);
            ctx.moveDown(6);

            ctx.writeText(fontNormal, FONT_SIZE_META, "Blank form — for manual completion");
            ctx.moveDown(4);
            ctx.drawHRule();
            ctx.moveDown(8);

            // ── Schema-driven field rendering (no data — every field renders empty) ──
            FormSchema schema = definition.getSchema();
            Map<String, Object> emptyData = Map.of();
            boolean inline = schema == null
                    || schema.getLabelPosition() == null
                    || "inline".equals(schema.getLabelPosition());

            if (schema != null && schema.getSections() != null) {
                for (FormSection section : schema.getSections()) {
                    renderSection(ctx, section, emptyData, fontBold, fontNormal, fontOblique, inline);
                }
            }

            // ── QR Code ──────────────────────────────────────────────
            try {
                byte[] qrPng = generateBlankQrCode(definition);
                if (qrPng != null) {
                    ctx.moveDown(12);
                    ctx.drawHRule();
                    ctx.moveDown(6);
                    if (ctx.getY() < BOTTOM_Y + QR_SIZE + 20) {
                        ctx.newPage();
                    }
                    PDImageXObject qrImage = PDImageXObject.createFromByteArray(doc, qrPng, "qr.png");
                    float qrX = PAGE_WIDTH - MARGIN - QR_SIZE;
                    float qrY = ctx.getY() - QR_SIZE;
                    ctx.getStream().drawImage(qrImage, qrX, qrY, QR_SIZE, QR_SIZE);
                    ctx.writeText(fontNormal, FONT_SIZE_META, "ECM Form QR — scan to link this document");
                    ctx.moveDown(QR_SIZE - FONT_SIZE_META);
                }
            } catch (Exception e) {
                log.warn("QR code generation failed for blank formKey={}: {}", definition.getFormKey(), e.getMessage());
            }

            ctx.close();
            doc.save(out);
            return out.toByteArray();
        }
    }

    // ── Section renderer ──────────────────────────────────────────────────────

    // Layout-only types that always break the row and render full-width.
    // LABEL is NOT here — it respects colSpan and flows inline with other fields.
    private static final java.util.Set<FieldType> LAYOUT_ONLY_TYPES =
            java.util.Set.of(FieldType.SECTION_HEADER, FieldType.PARAGRAPH, FieldType.DIVIDER);

    private void renderSection(PageContext ctx,
                               FormSection section,
                               Map<String, Object> data,
                               PDType1Font fontBold,
                               PDType1Font fontNormal,
                               PDType1Font fontOblique,
                               boolean inline) throws IOException {

        // Section title (if set and not blank)
        if (section.getTitle() != null && !section.getTitle().isBlank()) {
            ctx.moveDown(6);
            ctx.writeText(fontBold, FONT_SIZE_HEADING, safe(section.getTitle()));
            ctx.moveDown(3);
            ctx.drawHRule();
            ctx.moveDown(6);
        }

        if (section.getFields() == null) return;

        // ── Group fields into rows based on colSpan ──────────────────────
        // Walk the fields list. Accumulate colSpans until they reach or exceed 12.
        // Layout-only fields (PARAGRAPH, SECTION_HEADER, DIVIDER) always get their
        // own row at full width — they break any in-progress row.
        List<List<FormField>> rows = new java.util.ArrayList<>();
        List<FormField> currentRow = new java.util.ArrayList<>();
        int currentSpan = 0;

        for (FormField field : section.getFields()) {
            if (field.isHidden()) continue;

            // Layout-only fields break the row and render full-width
            if (LAYOUT_ONLY_TYPES.contains(field.getType())) {
                if (!currentRow.isEmpty()) {
                    rows.add(currentRow);
                    currentRow = new java.util.ArrayList<>();
                    currentSpan = 0;
                }
                rows.add(List.of(field)); // single-field row
                continue;
            }

            int span = field.getColSpan() != null ? field.getColSpan() : 12;

            // If adding this field exceeds 12 columns, flush current row first
            if (currentSpan + span > 12 && !currentRow.isEmpty()) {
                rows.add(currentRow);
                currentRow = new java.util.ArrayList<>();
                currentSpan = 0;
            }

            currentRow.add(field);
            currentSpan += span;

            // Row full — flush
            if (currentSpan >= 12) {
                rows.add(currentRow);
                currentRow = new java.util.ArrayList<>();
                currentSpan = 0;
            }
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }

        // ── Render rows ──────────────────────────────────────────────────
        for (List<FormField> row : rows) {
            if (row.size() == 1) {
                // Single field (full-width or layout-only)
                renderField(ctx, row.get(0), data, fontBold, fontNormal, fontOblique,
                            inline, MARGIN, PRINTABLE_W);
            } else {
                // Multi-field row — render side by side
                renderRow(ctx, row, data, fontBold, fontNormal, fontOblique, inline);
            }
        }

        ctx.moveDown(8); // gap between sections
    }

    // ── Row renderer (multi-column) ──────────────────────────────────────────

    /**
     * Renders multiple fields side-by-side in a single row.
     * Each field's width is proportional to its colSpan out of the total row span.
     * All fields in the row share the same y-position start; the cursor advances
     * by the tallest field's height after the row is done.
     */
    private void renderRow(PageContext ctx,
                           List<FormField> fields,
                           Map<String, Object> data,
                           PDType1Font fontBold,
                           PDType1Font fontNormal,
                           PDType1Font fontOblique,
                           boolean inline) throws IOException {

        // Calculate total span and proportional widths
        int totalSpan = fields.stream()
                .mapToInt(f -> f.getColSpan() != null ? f.getColSpan() : 12)
                .sum();
        if (totalSpan == 0) totalSpan = 12;

        float gap = 8f; // gap between columns
        float totalGaps = gap * (fields.size() - 1);
        float availableWidth = PRINTABLE_W - totalGaps;

        // Save y before rendering the row
        float rowStartY = ctx.getY();
        float lowestY = rowStartY; // track the bottom of the tallest cell

        float xOffset = MARGIN;
        for (int i = 0; i < fields.size(); i++) {
            FormField field = fields.get(i);
            int span = field.getColSpan() != null ? field.getColSpan() : 12;
            float cellWidth = (availableWidth * span) / totalSpan;

            // Reset y to row start for each field (same row)
            ctx.setY(rowStartY);

            renderField(ctx, field, data, fontBold, fontNormal, fontOblique,
                        inline, xOffset, cellWidth);

            // Track the lowest y (tallest cell determines row height)
            if (ctx.getY() < lowestY) {
                lowestY = ctx.getY();
            }

            xOffset += cellWidth + gap;
        }

        // Set cursor to the bottom of the tallest cell
        ctx.setY(lowestY);
    }

    // ── Field renderer ────────────────────────────────────────────────────────

    private void renderField(PageContext ctx,
                             FormField field,
                             Map<String, Object> data,
                             PDType1Font fontBold,
                             PDType1Font fontNormal,
                             PDType1Font fontOblique,
                             boolean inline,
                             float xOffset,
                             float cellWidth) throws IOException {

        FieldType type = field.getType();
        if (type == null) return;

        switch (type) {

            // ── Layout-only: PARAGRAPH ────────────────────────────────
            case PARAGRAPH: {
                String text = field.getLabel();
                if (text == null || text.isBlank()) return;
                ctx.writeWrapped(fontNormal, FONT_SIZE_PARA, safe(text), LINE_HEIGHT_PARA,
                                 xOffset, cellWidth);
                ctx.moveDown(6);
                break;
            }

            // ── Layout-only: SECTION_HEADER ───────────────────────────
            case SECTION_HEADER: {
                String text = field.getLabel();
                if (text == null || text.isBlank()) return;
                ctx.moveDown(4);
                ctx.writeTextAt(fontBold, FONT_SIZE_HEADING, safe(text), xOffset);
                ctx.moveDown(2);
                break;
            }

            // ── Layout-only: DIVIDER ──────────────────────────────────
            case DIVIDER: {
                ctx.moveDown(4);
                ctx.drawHRuleAt(xOffset, cellWidth);
                ctx.moveDown(4);
                break;
            }

            // ── Layout: LABEL — inline static text, respects colSpan ──
            case LABEL: {
                String text = field.getLabel();
                if (text == null || text.isBlank()) return;
                PDType1Font font = field.isRequired() ? fontBold : fontNormal;
                ctx.writeTextAt(font, FONT_SIZE_VALUE, safe(text), xOffset);
                ctx.moveDown(4);
                break;
            }

            // ── eSign: SIGNATURE — renders DocuSign anchor marker ────
            case SIGNATURE: {
                ctx.moveDown(4);
                ctx.drawHRuleAt(xOffset, cellWidth);
                ctx.moveDown(4);
                // Anchor string — DocuSign finds this and places a SignHere tab
                String sigAnchor = "/sig" + (field.getId() != null ? field.getId().hashCode() & 0xFFF : "1") + "/";
                ctx.writeTextAt(fontOblique, FONT_SIZE_LABEL,
                        safe(field.getLabel() != null ? field.getLabel() : "Signature"), xOffset);
                ctx.moveDown(2);
                // Render anchor (very small, acts as DocuSign marker)
                ctx.writeTextAt(fontNormal, 4f, sigAnchor, xOffset);
                ctx.writeTextAt(fontNormal, FONT_SIZE_VALUE,
                        "_________________________________________", xOffset);
                ctx.moveDown(8);
                break;
            }

            // ── eSign: INITIALS — renders DocuSign anchor marker ─────
            case INITIALS: {
                String initAnchor = "/init" + (field.getId() != null ? field.getId().hashCode() & 0xFFF : "1") + "/";
                ctx.writeTextAt(fontOblique, FONT_SIZE_LABEL,
                        safe(field.getLabel() != null ? field.getLabel() : "Initials"), xOffset);
                ctx.moveDown(2);
                ctx.writeTextAt(fontNormal, 4f, initAnchor, xOffset);
                ctx.writeTextAt(fontNormal, FONT_SIZE_VALUE, "________", xOffset);
                ctx.moveDown(8);
                break;
            }

            // ── eSign: SIGNER_EMAIL — renders as normal input field ──
            case SIGNER_EMAIL:
                // Falls through to default — renders as label + value like any input
                // DocuSign service reads this field's value as the recipient email

            // ── All input fields: label + value from submissionData ───
            default: {
                String label = field.getLabel() != null ? field.getLabel() : field.getKey();
                Object raw   = (data != null) ? data.get(field.getKey()) : null;
                String value = formatValue(raw);

                if (inline) {
                    // ── Inline layout: "Label:  value" on same line ───
                    String safeLabel = safe(label) + ":";
                    String safeValue = value.isBlank() ? "_______________" : safe(value);
                    float inlineLabelW = Math.min(LABEL_COL_WIDTH, cellWidth * 0.4f);

                    if (!value.isBlank() && value.contains("\n")) {
                        ctx.writeInlineAt(fontOblique, FONT_SIZE_VALUE, safeLabel,
                                          fontNormal, FONT_SIZE_VALUE, "",
                                          inlineLabelW, xOffset);
                        ctx.writeWrapped(fontNormal, FONT_SIZE_VALUE, safeValue,
                                         LINE_HEIGHT_NORMAL, xOffset + inlineLabelW,
                                         cellWidth - inlineLabelW);
                    } else {
                        ctx.writeInlineAt(fontOblique, FONT_SIZE_VALUE, safeLabel,
                                          fontNormal, FONT_SIZE_VALUE, safeValue,
                                          inlineLabelW, xOffset);
                    }
                    ctx.moveDown(4);
                } else {
                    // ── Stacked layout: label above value ─────────────
                    ctx.writeTextAt(fontOblique, FONT_SIZE_LABEL, safe(label), xOffset);
                    ctx.moveDown(1);

                    if (value.isBlank()) {
                        ctx.writeTextAt(fontNormal, FONT_SIZE_VALUE, "_______________", xOffset);
                    } else {
                        ctx.writeWrapped(fontNormal, FONT_SIZE_VALUE, safe(value),
                                         LINE_HEIGHT_NORMAL, xOffset, cellWidth);
                    }
                    ctx.moveDown(8);
                }
                break;
            }
        }
    }

    // ── Fallback: no schema snapshot ─────────────────────────────────────────

    private void renderRawDataFallback(PageContext ctx,
                                       Map<String, Object> data,
                                       PDType1Font fontBold,
                                       PDType1Font fontNormal) throws IOException {
        ctx.writeText(fontBold, FONT_SIZE_VALUE, "Form Data");
        ctx.moveDown(4);
        if (data == null || data.isEmpty()) {
            ctx.writeText(fontNormal, FONT_SIZE_VALUE, "(no data)");
            return;
        }
        for (Map.Entry<String, Object> e : data.entrySet()) {
            ctx.writeText(fontNormal, FONT_SIZE_LABEL, safe(e.getKey()));
            ctx.moveDown(1);
            ctx.writeWrapped(fontNormal, FONT_SIZE_VALUE, safe(formatValue(e.getValue())), LINE_HEIGHT_NORMAL);
            ctx.moveDown(6);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveFormTitle(FormSubmission submission) {
        FormSchema schema = submission.getFormSchemaSnapshot();
        // FormSchema has no top-level title field — use formKey (human-readable enough for now)
        return safe(submission.getFormKey() != null
                ? submission.getFormKey().replace("-", " ").replace("_", " ")
                : "Form Submission");
    }

    private String formatValue(Object raw) {
        if (raw == null) return "";
        if (raw instanceof List<?> list) {
            // CHECKBOX_GROUP submits a list of selected values
            return String.join(", ", list.stream().map(Object::toString).toList());
        }
        return raw.toString().trim();
    }

    /**
     * Strips characters outside WinAnsiEncoding (U+0020–U+00FF).
     * PDType1Font (Helvetica) uses WinAnsiEncoding — it supports all of
     * Latin-1 Supplement (accented chars like é, ñ, ü) but nothing above U+00FF.
     * The previous sanitiser [^\x20-\x7E] was too narrow: it rejected all accented
     * characters and box-drawing chars (which we now handle via line drawing instead).
     */
    private static String safe(String text) {
        if (text == null) return "";
        // Replace non-printable and above-Latin1 chars with '?'
        // Smart quotes (U+2018/2019/201C/201D) → straight quotes for readability
        return text
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u2013', '-').replace('\u2014', '-')
                .replaceAll("[^\\x20-\\xFF]", "?");
    }

    // ── PageContext — multi-page cursor ───────────────────────────────────────

    /**
     * Tracks current page + y-position across the document.
     * Automatically adds a new page and resets y when the cursor drops below BOTTOM_Y.
     * All drawing goes through this class — callers never touch PDPageContentStream directly.
     */
    private static class PageContext {
        private final PDDocument   doc;
        private final PDType1Font  fontBold;
        private final PDType1Font  fontNormal;
        private PDPageContentStream cs;
        private float y;

        PageContext(PDDocument doc, PDType1Font fontBold, PDType1Font fontNormal) throws IOException {
            this.doc        = doc;
            this.fontBold   = fontBold;
            this.fontNormal = fontNormal;
            newPage();
        }

        float getY() { return y; }
        void setY(float newY) { this.y = newY; }

        /** Close the current content stream. Must be called before doc.save(). */
        void close() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
        }

        /** Close current page stream and open a fresh one on a new page. */
        void newPage() throws IOException {
            if (cs != null) cs.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y  = TOP_Y;
        }

        /** Advance cursor down by {@code delta} points. Auto-page-breaks. */
        void moveDown(float delta) throws IOException {
            y -= delta;
            if (y < BOTTOM_Y) newPage();
        }

        /**
         * Write a single line of text. If the line itself would push y below BOTTOM_Y,
         * start a new page first. Caller is responsible for calling moveDown() after.
         */
        void writeText(PDType1Font font, float size, String text) throws IOException {
            if (y - size < BOTTOM_Y) newPage();
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(MARGIN, y);
            cs.showText(text);
            cs.endText();
            y -= size + 2;   // advance by font size + small gap
        }

        /** Write text at a custom x-offset (for multi-column layout). */
        void writeTextAt(PDType1Font font, float size, String text, float xOffset) throws IOException {
            if (y - size < BOTTOM_Y) newPage();
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(xOffset, y);
            cs.showText(text);
            cs.endText();
            y -= size + 2;
        }

        /**
         * Word-wrap {@code text} into lines of at most PRINTABLE_W points,
         * writing each line and auto-page-breaking as needed.
         *
         * Uses simple word-boundary splitting. PDType1Font.getStringWidth()
         * returns width in 1/1000 pt units — divide by 1000 then multiply by size.
         */
        /** Word-wrap at full page width starting at MARGIN. */
        void writeWrapped(PDType1Font font, float size, String text, float lineHeight)
                throws IOException {
            writeWrapped(font, size, text, lineHeight, MARGIN, PRINTABLE_W);
        }

        /**
         * Draw a full-width horizontal rule at the current y position.
         * Uses PDF line-drawing primitives (not text characters) so it always
         * renders correctly regardless of font encoding.
         */
        void drawHRule() throws IOException {
            if (y - 4 < BOTTOM_Y) newPage();
            cs.setLineWidth(0.5f);
            cs.moveTo(MARGIN, y);
            cs.lineTo(PAGE_WIDTH - MARGIN, y);
            cs.stroke();
            y -= 4;
        }

        /** Draw a horizontal rule at a custom x-offset and width. */
        void drawHRuleAt(float xOffset, float width) throws IOException {
            if (y - 4 < BOTTOM_Y) newPage();
            cs.setLineWidth(0.5f);
            cs.moveTo(xOffset, y);
            cs.lineTo(xOffset + width, y);
            cs.stroke();
            y -= 4;
        }

        /**
         * Write label and value on the same line (inline layout).
         * Label is left-aligned at MARGIN; value starts after label text + gap.
         */
        void writeInline(PDType1Font labelFont, float labelSize, String label,
                         PDType1Font valueFont, float valueSize, String value,
                         float labelWidth) throws IOException {
            writeInlineAt(labelFont, labelSize, label, valueFont, valueSize, value,
                          labelWidth, MARGIN);
        }

        /**
         * Write inline label+value at a custom x-offset (for multi-column layout).
         * Label is left-aligned at xOffset. Value starts after label's actual
         * text width + 6pt gap — so short labels leave more room for values.
         */
        void writeInlineAt(PDType1Font labelFont, float labelSize, String label,
                           PDType1Font valueFont, float valueSize, String value,
                           float labelWidth, float xOffset) throws IOException {
            float lineH = Math.max(labelSize, valueSize) + 4;
            if (y - lineH < BOTTOM_Y) newPage();

            float gap = 6f;

            // Label — left-aligned at xOffset
            float labelTextW = labelFont.getStringWidth(label) / 1000f * labelSize;
            cs.beginText();
            cs.setFont(labelFont, labelSize);
            cs.newLineAtOffset(xOffset, y);
            cs.showText(label);
            cs.endText();

            // Value — starts after label text + gap, left-aligned
            if (value != null && !value.isEmpty()) {
                float valueX = xOffset + labelTextW + gap;
                cs.beginText();
                cs.setFont(valueFont, valueSize);
                cs.newLineAtOffset(valueX, y);
                cs.showText(value);
                cs.endText();
            }

            y -= lineH;
        }

        /**
         * Word-wrap with custom x-offset and max width (used for multi-column + inline layout).
         */
        void writeWrapped(PDType1Font font, float size, String text, float lineHeight,
                          float xOffset, float maxWidth) throws IOException {
            if (text == null || text.isBlank()) return;

            for (String paragraph : text.split("\\r?\\n", -1)) {
                String[] words = paragraph.split(" ", -1);
                StringBuilder line = new StringBuilder();

                for (String word : words) {
                    String candidate = line.length() == 0 ? word : line + " " + word;
                    float w = font.getStringWidth(candidate) / 1000f * size;

                    if (w > maxWidth && line.length() > 0) {
                        writeSingleLineAt(font, size, line.toString(), lineHeight, xOffset);
                        line = new StringBuilder(word);
                    } else {
                        line = new StringBuilder(candidate);
                    }
                }
                if (line.length() > 0) {
                    writeSingleLineAt(font, size, line.toString(), lineHeight, xOffset);
                }
            }
        }

        private void writeSingleLineAt(PDType1Font font, float size, String text,
                                        float lineHeight, float xOffset) throws IOException {
            if (y - lineHeight < BOTTOM_Y) newPage();
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(xOffset, y);
            cs.showText(text);
            cs.endText();
            y -= lineHeight;
        }

        /** Expose content stream for image drawing (QR code). */
        PDPageContentStream getStream() { return cs; }
    }

    // ── QR Code generation ────────────────────────────────────────────────────

    /**
     * Generates a QR code PNG containing form context metadata.
     * Payload is compact JSON:
     *   ecm  — marker (always true)
     *   fk   — formKey
     *   v    — form version
     *   fd   — formDefinitionId
     *   sid  — submissionId (null for blank prints)
     *   cid  — caseId (only if the form was filled from within a case context)
     *   cki  — checklist item id within that case (only alongside cid)
     *   pid  — partyExternalId (customer)
     */
    private byte[] generateQrCode(FormSubmission submission) {
        Map<String, Object> qrPayload = new LinkedHashMap<>();
        qrPayload.put("ecm", true);
        qrPayload.put("fk", submission.getFormKey());
        qrPayload.put("v", submission.getFormVersion());
        if (submission.getFormDefinition() != null) {
            qrPayload.put("fd", submission.getFormDefinition().getId().toString());
        }
        qrPayload.put("sid", submission.getId().toString());
        if (submission.getPartyExternalId() != null) {
            qrPayload.put("pid", submission.getPartyExternalId());
        }
        // Case context — injected by FormFillPage into submissionData when a form is
        // filled from within a case (see FormDocumentCreationService.linkToCaseChecklist,
        // the same convention this mirrors for the QR fast-path in ecm-batch).
        Map<String, Object> data = submission.getSubmissionData();
        if (data != null && data.get("_caseId") != null && data.get("_checklistItemId") != null) {
            qrPayload.put("cid", data.get("_caseId").toString());
            qrPayload.put("cki", data.get("_checklistItemId").toString());
        }
        return qrJsonToPng(qrPayload);
    }

    /**
     * QR payload for a blank print — no sid (no submission exists yet) and no pid
     * (no customer known yet). Scanning it back in can only resolve the form/category,
     * never the customer — that still has to come from OCR/customer-matching.
     */
    private byte[] generateBlankQrCode(FormDefinition definition) {
        Map<String, Object> qrPayload = new LinkedHashMap<>();
        qrPayload.put("ecm", true);
        qrPayload.put("fk", definition.getFormKey());
        qrPayload.put("v", definition.getVersion());
        qrPayload.put("fd", definition.getId().toString());
        return qrJsonToPng(qrPayload);
    }

    private byte[] qrJsonToPng(Map<String, Object> qrPayload) {
        try {
            String json = objectMapper.writeValueAsString(qrPayload);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(json, BarcodeFormat.QR_CODE, 200, 200);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("Failed to generate QR code: {}", e.getMessage());
            return null;
        }
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    public static class PdfGenerationException extends RuntimeException {
        public PdfGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}