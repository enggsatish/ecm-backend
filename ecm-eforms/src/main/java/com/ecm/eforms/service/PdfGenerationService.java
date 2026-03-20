package com.ecm.eforms.service;

import com.ecm.eforms.model.entity.FormSubmission;
import com.ecm.eforms.model.schema.FieldType;
import com.ecm.eforms.model.schema.FormField;
import com.ecm.eforms.model.schema.FormSchema;
import com.ecm.eforms.model.schema.FormSection;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

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
public class PdfGenerationService {

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

            if (schema != null && schema.getSections() != null) {
                for (FormSection section : schema.getSections()) {
                    renderSection(ctx, section, data, fontBold, fontNormal, fontOblique);
                }
            } else {
                // Fallback: schema snapshot unavailable — dump raw submissionData map
                log.warn("formSchemaSnapshot is null for submission={} — falling back to raw data dump",
                        submission.getId());
                renderRawDataFallback(ctx, data, fontBold, fontNormal);
            }

            // ── Signature block ───────────────────────────────────────
            ctx.moveDown(20);
            // Ensure there's room for the signature block; if not, start a new page
            if (ctx.getY() < BOTTOM_Y + 60) {
                ctx.newPage();
            }
            ctx.drawHRule();
            ctx.moveDown(8);
            ctx.writeText(fontBold, FONT_SIZE_LABEL, "APPLICANT SIGNATURE");
            ctx.moveDown(6);
            ctx.writeText(fontNormal, FONT_SIZE_VALUE,
                    "Signature: _________________________________    Date: _______________");
            ctx.moveDown(4);
            ctx.writeText(fontNormal, FONT_SIZE_VALUE,
                    "Print Name: ________________________________");

            ctx.close();
            doc.save(out);
            return out.toByteArray();
        }
    }

    // ── Section renderer ──────────────────────────────────────────────────────

    private void renderSection(PageContext ctx,
                               FormSection section,
                               Map<String, Object> data,
                               PDType1Font fontBold,
                               PDType1Font fontNormal,
                               PDType1Font fontOblique) throws IOException {

        // Section title (if set and not blank)
        if (section.getTitle() != null && !section.getTitle().isBlank()) {
            ctx.moveDown(6);
            ctx.writeText(fontBold, FONT_SIZE_HEADING, safe(section.getTitle()));
            ctx.moveDown(3);
            ctx.drawHRule();
            ctx.moveDown(6);
        }

        if (section.getFields() == null) return;

        for (FormField field : section.getFields()) {
            if (field.isHidden()) continue;
            renderField(ctx, field, data, fontBold, fontNormal, fontOblique);
        }

        ctx.moveDown(8); // gap between sections
    }

    // ── Field renderer ────────────────────────────────────────────────────────

    private void renderField(PageContext ctx,
                             FormField field,
                             Map<String, Object> data,
                             PDType1Font fontBold,
                             PDType1Font fontNormal,
                             PDType1Font fontOblique) throws IOException {

        FieldType type = field.getType();
        if (type == null) return;

        switch (type) {

            // ── Layout-only: PARAGRAPH ────────────────────────────────
            // field.label holds the paragraph body text authored in the designer.
            // It is NEVER in submissionData — render from the schema field directly.
            case PARAGRAPH: {
                String text = field.getLabel();
                if (text == null || text.isBlank()) return;
                // Paragraph text can be long — always word-wrap it
                ctx.writeWrapped(fontNormal, FONT_SIZE_PARA, safe(text), LINE_HEIGHT_PARA);
                ctx.moveDown(6);
                break;
            }

            // ── Layout-only: SECTION_HEADER ───────────────────────────
            case SECTION_HEADER: {
                String text = field.getLabel();
                if (text == null || text.isBlank()) return;
                ctx.moveDown(4);
                ctx.writeText(fontBold, FONT_SIZE_HEADING, safe(text));
                ctx.moveDown(2);
                break;
            }

            // ── Layout-only: DIVIDER ──────────────────────────────────
            case DIVIDER: {
                ctx.moveDown(4);
                ctx.drawHRule();
                ctx.moveDown(4);
                break;
            }

            // ── All input fields: label + value from submissionData ───
            default: {
                String label = field.getLabel() != null ? field.getLabel() : field.getKey();
                Object raw   = (data != null) ? data.get(field.getKey()) : null;
                String value = formatValue(raw);

                // Field label (small, grey-ish via oblique — italic stands out from body)
                ctx.writeText(fontOblique, FONT_SIZE_LABEL, safe(label));
                ctx.moveDown(1);

                // Field value (larger, normal weight — visually prominent)
                if (value.isBlank()) {
                    // No value entered — show a placeholder underline
                    ctx.writeText(fontNormal, FONT_SIZE_VALUE, "____________________________");
                } else {
                    // Value may be multi-line (TEXT_AREA) — word-wrap it
                    ctx.writeWrapped(fontNormal, FONT_SIZE_VALUE, safe(value), LINE_HEIGHT_NORMAL);
                }
                ctx.moveDown(8);
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

        /**
         * Word-wrap {@code text} into lines of at most PRINTABLE_W points,
         * writing each line and auto-page-breaking as needed.
         *
         * Uses simple word-boundary splitting. PDType1Font.getStringWidth()
         * returns width in 1/1000 pt units — divide by 1000 then multiply by size.
         */
        void writeWrapped(PDType1Font font, float size, String text, float lineHeight)
                throws IOException {
            if (text == null || text.isBlank()) return;

            // Split on \n first (TEXT_AREA may have explicit line breaks)
            for (String paragraph : text.split("\\r?\\n", -1)) {
                String[] words = paragraph.split(" ", -1);
                StringBuilder line = new StringBuilder();

                for (String word : words) {
                    String candidate = line.length() == 0 ? word : line + " " + word;
                    float w = font.getStringWidth(candidate) / 1000f * size;

                    if (w > PRINTABLE_W && line.length() > 0) {
                        // Flush current line and start a new one with this word
                        writeSingleLine(font, size, line.toString(), lineHeight);
                        line = new StringBuilder(word);
                    } else {
                        line = new StringBuilder(candidate);
                    }
                }
                // Flush remaining words
                if (line.length() > 0) {
                    writeSingleLine(font, size, line.toString(), lineHeight);
                }
            }
        }

        private void writeSingleLine(PDType1Font font, float size, String text, float lineHeight)
                throws IOException {
            if (y - lineHeight < BOTTOM_Y) newPage();
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(MARGIN, y);
            cs.showText(text);
            cs.endText();
            y -= lineHeight;
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
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    public static class PdfGenerationException extends RuntimeException {
        public PdfGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}