package com.ecm.eforms.service;

import com.ecm.eforms.model.entity.FormSubmission;
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
import java.util.Map;

/**
 * PDF Generation Service — Sprint 1 Skeleton
 * ═══════════════════════════════════════════
 *
 * Sprint 1: Generates a minimal plain-text PDF summary of the form submission.
 *           Sufficient to send to DocuSign for signature placement.
 *
 * Sprint 2 full implementation will:
 *   - Load a DOCX/PDF template from MinIO (form_definitions.document_template_id)
 *   - Map submission_data fields to template placeholders via docuSignAnchor tags
 *   - Apply branded layout (logo, colours from ui_config)
 *   - Embed QR code with submission ID for tracking
 *   - Store generated PDF in MinIO ecm-documents bucket
 *   - Return the MinIO object UUID (stored as FormSubmission.draftDocumentId)
 *
 * Apache PDFBox 3.x API is used (not iText — avoids AGPL licensing concerns).
 */
@Service
@Slf4j
public class PdfGenerationService {

    private static final float MARGIN      = 50f;
    private static final float LINE_HEIGHT = 16f;
    private static final float FONT_SIZE   = 11f;
    private static final float TITLE_SIZE  = 16f;

    /**
     * Generates a PDF from the form submission and returns the raw bytes.
     *
     * Sprint 1: plain-text summary layout.
     * Sprint 2: template-based layout stored in MinIO.
     *
     * @param submission the completed FormSubmission entity
     * @return raw PDF bytes ready for MinIO storage or DocuSign upload
     */
    public byte[] generate(FormSubmission submission) {
        log.info("Generating PDF for submission={}, formKey={}",
                submission.getId(), submission.getFormKey());

        try {
            byte[] pdfBytes = buildPlainPdf(submission);
            log.info("PDF generated: {} bytes for submission={}", pdfBytes.length, submission.getId());
            return pdfBytes;

        } catch (IOException e) {
            log.error("PDF generation failed for submission={}: {}", submission.getId(), e.getMessage(), e);
            throw new PdfGenerationException("Failed to generate PDF for submission " + submission.getId(), e);
        }
    }

    // ─── Sprint 1: plain text PDF ─────────────────────────────────────

    private byte[] buildPlainPdf(FormSubmission submission) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            float pageHeight = page.getMediaBox().getHeight();
            float yPos       = pageHeight - MARGIN;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {

                PDType1Font boldFont   = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
                PDType1Font normalFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

                // ── Title ──────────────────────────────────────────────
                yPos = writeLine(cs, boldFont, TITLE_SIZE,
                        submission.getFormKey() + " — Form Submission",
                        MARGIN, yPos);
                yPos -= 8;

                // ── Meta ───────────────────────────────────────────────
                String submittedAt = submission.getSubmittedAt() != null
                        ? submission.getSubmittedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        : "Draft";

                yPos = writeLine(cs, normalFont, FONT_SIZE,
                        "Submission ID : " + submission.getId(), MARGIN, yPos);
                yPos = writeLine(cs, normalFont, FONT_SIZE,
                        "Form Version  : " + submission.getFormVersion(), MARGIN, yPos);
                yPos = writeLine(cs, normalFont, FONT_SIZE,
                        "Submitted By  : " + submission.getSubmittedByName()
                                + " (" + submission.getSubmittedBy() + ")", MARGIN, yPos);
                yPos = writeLine(cs, normalFont, FONT_SIZE,
                        "Submitted At  : " + submittedAt, MARGIN, yPos);
                yPos = writeLine(cs, normalFont, FONT_SIZE,
                        "Status        : " + submission.getStatus(), MARGIN, yPos);
                yPos -= 12;

                // ── Divider ────────────────────────────────────────────
                yPos = writeLine(cs, boldFont, FONT_SIZE,
                        "─────────────────────────────────────────", MARGIN, yPos);
                yPos -= 4;

                // ── Field values ───────────────────────────────────────
                yPos = writeLine(cs, boldFont, FONT_SIZE, "Form Data:", MARGIN, yPos);
                yPos -= 4;

                Map<String, Object> data = submission.getSubmissionData();
                if (data != null) {
                    for (Map.Entry<String, Object> entry : data.entrySet()) {
                        if (yPos < MARGIN + LINE_HEIGHT) break; // avoid overflow in Sprint 1
                        String line = "  " + entry.getKey() + " : "
                                + (entry.getValue() != null ? entry.getValue().toString() : "");
                        yPos = writeLine(cs, normalFont, FONT_SIZE, line, MARGIN, yPos);
                    }
                }

                // ── DocuSign signature anchor ──────────────────────────
                // Sprint 2: replace with per-field anchor tags
                // For now, add a plain placeholder at the bottom
                yPos = Math.min(yPos - 40, 120f);
                yPos = writeLine(cs, normalFont, FONT_SIZE,
                        "Applicant Signature: ________________________   Date: ____________",
                        MARGIN, yPos);
            }

            doc.save(out);
            return out.toByteArray();
        }
    }

    private float writeLine(PDPageContentStream cs, PDType1Font font, float size,
                            String text, float x, float y) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        // Sanitise: PDFBox Type1 fonts only accept ISO-8859-1
        String safe = text.replaceAll("[^\\x20-\\x7E]", "?");
        cs.showText(safe);
        cs.endText();
        return y - LINE_HEIGHT;
    }

    // ─── Exception ────────────────────────────────────────────────────

    public static class PdfGenerationException extends RuntimeException {
        public PdfGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}