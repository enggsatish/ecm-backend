package com.ecm.eforms.model.schema;

/**
 * All supported field types for the low-code form designer.
 *
 * MVP (Sprint 1):  input types the drag-drop designer exposes in the palette.
 * PHASE2:          more complex types added progressively.
 */
public enum FieldType {
    // ── Input ──────────────────────────────────────────
    TEXT_INPUT,
    TEXT_AREA,
    NUMBER,
    EMAIL,
    PHONE,
    DATE,
    // ── Choice ─────────────────────────────────────────
    DROPDOWN,
    OPTION_BUTTON,      // radio group
    CHECKBOX,           // single boolean
    CHECKBOX_GROUP,     // multi-select checkboxes
    // ── Layout ─────────────────────────────────────────
    SECTION_HEADER,
    PARAGRAPH,          // read-only text / instructions
    DIVIDER,
    // ── Phase 2 ────────────────────────────────────────
    DATE_RANGE,
    CURRENCY,
    ADDRESS_BLOCK,
    SSN,
    TABLE,
    FILE_UPLOAD,
    SIGNATURE_PAD,
    LOOKUP,
    CALCULATED_FIELD,
    RICH_TEXT,
    RATING,
    SLIDER,
    HIDDEN
}
