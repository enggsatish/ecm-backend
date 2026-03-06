package com.ecm.eforms.service;

import com.ecm.eforms.model.schema.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Validates submission_data against the form schema + rule engine.
 *
 * Two-phase validation:
 *   Phase 1: Run the rule engine to find hidden/disabled fields.
 *   Phase 2: Validate ONLY visible, non-disabled fields.
 *            Hidden fields are excluded — users cannot fill what they cannot see.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FormValidationService {

    private final RuleEngineService ruleEngine;

    public record ValidationResult(
        boolean                    valid,
        Map<String, List<String>>  fieldErrors,
        List<String>               formErrors
    ) {}

    public ValidationResult validate(FormSchema schema, Map<String, Object> data) {
        Map<String, List<String>> fieldErrors = new LinkedHashMap<>();
        List<String>              formErrors  = new ArrayList<>();

        if (schema == null || data == null) return new ValidationResult(true, fieldErrors, formErrors);

        // Phase 1 — determine hidden fields
        RuleEngineService.RuleEvaluationResult ruleResult = ruleEngine.evaluate(schema, data);
        Set<String> hiddenFields  = ruleResult.hiddenFields();
        Set<String> ruleRequired  = ruleResult.ruleRequiredFields();
        formErrors.addAll(ruleResult.blockingMessages());

        // Phase 2 — validate each visible field
        if (schema.getSections() != null) {
            for (FormSection section : schema.getSections()) {
                if (ruleResult.hiddenSections().contains(section.getId())) continue;
                if (section.getFields() == null) continue;

                for (FormField field : section.getFields()) {
                    if (hiddenFields.contains(field.getKey())) continue;

                    List<String> errors = validateField(field, data, ruleRequired);
                    if (!errors.isEmpty()) fieldErrors.put(field.getKey(), errors);
                }
            }
        }

        return new ValidationResult(fieldErrors.isEmpty() && formErrors.isEmpty(), fieldErrors, formErrors);
    }

    // ── Per-field validation ──────────────────────────────────────────

    private List<String> validateField(FormField field, Map<String, Object> data, Set<String> ruleRequired) {
        List<String> errors = new ArrayList<>();
        Object value = data.get(field.getKey());
        boolean effectiveRequired = field.isRequired() || ruleRequired.contains(field.getKey());

        // Required check
        if (effectiveRequired && isEmpty(value)) {
            errors.add(field.getLabel() + " is required");
            return errors; // no further checks if empty+required
        }
        if (isEmpty(value)) return errors; // optional and empty — valid

        FieldValidation v = field.getValidation();
        if (v == null) return errors;

        String str = value.toString();

        // String constraints
        if (v.getMinLength() != null && str.length() < v.getMinLength())
            errors.add(field.getLabel() + " must be at least " + v.getMinLength() + " characters");
        if (v.getMaxLength() != null && str.length() > v.getMaxLength())
            errors.add(field.getLabel() + " must be at most " + v.getMaxLength() + " characters");
        if (v.getPattern() != null && !Pattern.matches(v.getPattern(), str))
            errors.add(v.getCustomMessage() != null ? v.getCustomMessage() : field.getLabel() + " format is invalid");

        // Numeric constraints
        if (field.getType() == FieldType.NUMBER || field.getType() == FieldType.CURRENCY) {
            try {
                double num = Double.parseDouble(str);
                if (v.getMin() != null && num < v.getMin())
                    errors.add(field.getLabel() + " must be at least " + v.getMin());
                if (v.getMax() != null && num > v.getMax())
                    errors.add(field.getLabel() + " must be at most " + v.getMax());
            } catch (NumberFormatException e) {
                errors.add(field.getLabel() + " must be a valid number");
            }
        }

        // Format validators
        if (v.isEmailFormat() && !str.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            errors.add(field.getLabel() + " must be a valid email address");
        if (v.isPhoneFormat() && !str.matches("^[\\+]?[(]?[0-9]{1,4}[)]?[-.\\s]?[0-9]{3}[-.\\s]?[0-9]{4,9}$"))
            errors.add(field.getLabel() + " must be a valid phone number");

        // Date constraints
        if (field.getType() == FieldType.DATE) {
            try {
                LocalDate date = LocalDate.parse(str, DateTimeFormatter.ISO_DATE);
                if (v.getMinDate() != null) {
                    LocalDate min = parseDate(v.getMinDate());
                    if (date.isBefore(min)) errors.add(field.getLabel() + " must be on or after " + min);
                }
                if (v.getMaxDate() != null) {
                    LocalDate max = parseDate(v.getMaxDate());
                    if (date.isAfter(max)) errors.add(field.getLabel() + " must be on or before " + max);
                }
            } catch (Exception e) {
                errors.add(field.getLabel() + " must be a valid date (YYYY-MM-DD)");
            }
        }

        return errors;
    }

    private boolean isEmpty(Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isBlank();
        if (v instanceof Collection<?> c) return c.isEmpty();
        return false;
    }

    private LocalDate parseDate(String expr) {
        if (expr.equalsIgnoreCase("TODAY")) return LocalDate.now();
        if (expr.startsWith("TODAY+")) return LocalDate.now().plusDays(Long.parseLong(expr.substring(6)));
        if (expr.startsWith("TODAY-")) return LocalDate.now().minusDays(Long.parseLong(expr.substring(6)));
        return LocalDate.parse(expr, DateTimeFormatter.ISO_DATE);
    }
}
