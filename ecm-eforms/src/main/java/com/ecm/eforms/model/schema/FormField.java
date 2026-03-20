package com.ecm.eforms.model.schema;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * A single form field as stored in FormSection.fields[].
 *
 * Grid layout: fields are placed in a 12-column grid.
 * colSpan defaults to 12 (full width). Set to 6 for two-column layout.
 *
 * ── IMPORTANT: defaultValue is JsonNode, NOT Object ──────────────────────────
 * Using Object caused "Cannot read while there is an open stream writer"
 * (Jackson StreamWriteException) when Hypersistence serialized FormSchema to
 * JSONB. Root cause: Jackson enters a re-entrant serialization context when it
 * encounters a raw Object and tries to determine its runtime type inside an
 * already-open JsonGenerator.
 *
 * JsonNode is Jackson's own tree model — handled directly without any
 * re-entrant calls. Wire format is IDENTICAL: null, "string", 42, true,
 * ["a","b"], {"k":"v"} all round-trip correctly. Frontend FormRenderer is
 * unaffected — just call .asText() / .asInt() / .asBoolean() where needed.
 */
@Data
public class FormField {
    private String          id;             // unique within the form, e.g. "f_applicant_name"
    private FieldType       type;
    private String          key;            // submission_data key, e.g. "applicantName"
    private String          label;
    private String          placeholder;
    private String          helpText;
    private boolean         required    = false;
    private boolean         hidden      = false;
    private boolean         disabled    = false;
    private boolean         readonly    = false;
    private Integer         colSpan     = 12;   // 1-12 grid columns

    private FieldValidation validation;
    private List<FieldOption> options;           // for DROPDOWN, OPTION_BUTTON, CHECKBOX_GROUP
    private List<RuleDsl.RuleSet> rules;         // field-level conditional rules

    /** DocuSign anchor tag — Sprint 2: positions the signature tab on the PDF */
    private String          docuSignAnchor;

    /** Lookup config for LOOKUP field type (Phase 2) */
    private Map<String, Object> lookupConfig;

    /**
     * Default value pre-populated when the form loads.
     *
     * Type is JsonNode instead of Object to avoid Jackson stream re-entrancy
     * during JSONB serialization. Frontend usage:
     *   String  → field.defaultValue?.asText()
     *   Number  → field.defaultValue?.asInt() or asDouble()
     *   Boolean → field.defaultValue?.asBoolean()
     *   null    → field.defaultValue == null || field.defaultValue.isNull()
     *
     * The Form Designer stores defaultValue as a raw JSON value — this mapping
     * preserves it exactly without type coercion.
     */
    private JsonNode        defaultValue;
}