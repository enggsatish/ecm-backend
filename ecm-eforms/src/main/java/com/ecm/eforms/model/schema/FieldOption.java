package com.ecm.eforms.model.schema;

import lombok.Data;

/** A single selectable option in DROPDOWN, OPTION_BUTTON, CHECKBOX_GROUP. */
@Data
public class FieldOption {
    private String  value;
    private String  label;
    private boolean disabled = false;
}
