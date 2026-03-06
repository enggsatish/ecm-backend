package com.ecm.eforms.model.schema;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Root schema stored as JSONB in form_definitions.schema.
 *
 * This is the complete form definition including all sections, fields,
 * rules, and rendering configuration. It is also snapshotted into
 * form_submissions.form_schema_snapshot at submission time for compliance.
 *
 * Layout options:
 *   SINGLE_PAGE — all sections rendered at once, single submit button
 *   WIZARD      — each section is a separate step; back/next navigation
 */
@Data
public class FormSchema {
    private List<FormSection>     sections;
    private List<RuleDsl.RuleSet> globalRules;    // cross-field rules
    private String                layout       = "SINGLE_PAGE"; // SINGLE_PAGE | WIZARD
    private boolean               allowSaveDraft = true;
    private boolean               confirmOnSubmit = false;
    private Integer               estimatedMinutes;
    private String                submitButtonLabel = "Submit";
    private String                draftButtonLabel  = "Save Draft";
    private Map<String, Object>   uiConfig;       // branding overrides per form
}
