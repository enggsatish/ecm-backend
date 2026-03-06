package com.ecm.eforms.model.schema;

import lombok.Data;

/**
 * Validation constraints stored on each FormField.
 * Applied by FormValidationService server-side and the renderer client-side.
 */
@Data
public class FieldValidation {
    private Integer minLength;
    private Integer maxLength;
    private String  pattern;       // regex
    private Double  min;           // numeric minimum
    private Double  max;           // numeric maximum
    private String  minDate;       // ISO date or expression: TODAY, TODAY-30
    private String  maxDate;
    private boolean emailFormat;
    private boolean phoneFormat;
    private boolean urlFormat;
    private boolean postalCodeFormat;
    private String  customMessage; // override default error message
}
