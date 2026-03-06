package com.ecm.eforms.model.schema;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * A single form field as stored in FormSection.fields[].
 *
 * Grid layout: fields are placed in a 12-column grid.
 * colSpan defaults to 12 (full width). Set to 6 for two-column layout.
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

    /** Default value pre-populated when the form loads */
    private Object          defaultValue;
}
