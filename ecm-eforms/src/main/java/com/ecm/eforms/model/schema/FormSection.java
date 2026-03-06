package com.ecm.eforms.model.schema;

import lombok.Data;
import java.util.List;

/**
 * A logical grouping of fields within a form.
 * In WIZARD layout, each section becomes a separate step/page.
 */
@Data
public class FormSection {
    private String          id;
    private String          title;
    private String          description;
    private List<FormField> fields;
    private boolean         collapsible = false;
    private boolean         hidden      = false;
    private Integer         page        = 1;     // used in WIZARD layout
    private String          condition;           // section-level visibility expression
}
