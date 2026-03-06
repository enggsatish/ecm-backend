package com.ecm.eforms.service;

import com.ecm.eforms.model.schema.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Server-side rule engine — mirrors the client-side JS rule evaluator.
 *
 * Both sides must evaluate identically so that:
 *   1. The UI hides/shows fields based on user input in real-time
 *   2. The server rejects submissions that bypass hidden required fields
 *   3. ON_SUBMIT rules are always enforced regardless of client state
 *
 * Usage:
 *   RuleEvaluationResult result = ruleEngine.evaluate(schema, submissionData);
 *   result.hiddenFields()    → Set of field keys that should be hidden
 *   result.blockingMessages() → List of error messages that block submission
 */
@Service
@Slf4j
public class RuleEngineService {

    // ── Public API ────────────────────────────────────────────────────

    public record RuleEvaluationResult(
        Set<String>         hiddenFields,
        Set<String>         disabledFields,
        Set<String>         ruleRequiredFields,
        Set<String>         hiddenSections,
        List<String>        blockingMessages,
        Map<String, Object> computedValues
    ) {}

    public RuleEvaluationResult evaluate(FormSchema schema, Map<String, Object> data) {
        Set<String>         hidden      = new HashSet<>();
        Set<String>         disabled    = new HashSet<>();
        Set<String>         required    = new HashSet<>();
        Set<String>         hiddenSec   = new HashSet<>();
        List<String>        blocking    = new ArrayList<>();
        Map<String, Object> computed    = new HashMap<>();

        if (schema == null || data == null) return empty();

        List<RuleDsl.RuleSet> allRules = new ArrayList<>();
        if (schema.getGlobalRules() != null) allRules.addAll(schema.getGlobalRules());

        if (schema.getSections() != null) {
            for (FormSection section : schema.getSections()) {
                if (section.getFields() == null) continue;
                for (FormField field : section.getFields()) {
                    if (field.getRules() != null) allRules.addAll(field.getRules());
                }
            }
        }

        for (RuleDsl.RuleSet rule : allRules) {
            boolean matches = evaluateCondition(rule.getCondition(), data);
            List<RuleDsl.RuleAction> actions = matches ? rule.getActions() : rule.getElseActions();
            if (actions == null) continue;
            applyActions(actions, hidden, disabled, required, hiddenSec, blocking, computed, data);
        }

        return new RuleEvaluationResult(hidden, disabled, required, hiddenSec, blocking, computed);
    }

    // ── Condition evaluation ──────────────────────────────────────────

    private boolean evaluateCondition(RuleDsl.Condition cond, Map<String, Object> data) {
        if (cond == null) return true;

        List<Boolean> results = new ArrayList<>();

        if (cond.getClauses() != null) {
            for (RuleDsl.Clause clause : cond.getClauses())
                results.add(evaluateClause(clause, data));
        }
        if (cond.getSubConditions() != null) {
            for (RuleDsl.Condition sub : cond.getSubConditions())
                results.add(evaluateCondition(sub, data));
        }

        if (results.isEmpty()) return true;
        return "OR".equalsIgnoreCase(cond.getLogic())
            ? results.stream().anyMatch(Boolean::booleanValue)
            : results.stream().allMatch(Boolean::booleanValue);
    }

    private boolean evaluateClause(RuleDsl.Clause clause, Map<String, Object> data) {
        Object fieldVal = data.get(clause.getField());
        Object ruleVal  = clause.getValue();

        return switch (clause.getOperator()) {
            case EQUALS         -> equals(fieldVal, ruleVal);
            case NOT_EQUALS     -> !equals(fieldVal, ruleVal);
            case CONTAINS       -> containsStr(fieldVal, ruleVal);
            case NOT_CONTAINS   -> !containsStr(fieldVal, ruleVal);
            case STARTS_WITH    -> startsWith(fieldVal, ruleVal);
            case ENDS_WITH      -> endsWith(fieldVal, ruleVal);
            case GREATER_THAN   -> compare(fieldVal, ruleVal) > 0;
            case LESS_THAN      -> compare(fieldVal, ruleVal) < 0;
            case GREATER_OR_EQUAL -> compare(fieldVal, ruleVal) >= 0;
            case LESS_OR_EQUAL  -> compare(fieldVal, ruleVal) <= 0;
            case BETWEEN        -> isBetween(fieldVal, ruleVal, clause.getValueTo());
            case IS_EMPTY       -> isEmpty(fieldVal);
            case IS_NOT_EMPTY   -> !isEmpty(fieldVal);
            case IN             -> isIn(fieldVal, ruleVal);
            case NOT_IN         -> !isIn(fieldVal, ruleVal);
            case BEFORE_DATE    -> compareDate(fieldVal, ruleVal) < 0;
            case AFTER_DATE     -> compareDate(fieldVal, ruleVal) > 0;
            case DATE_BETWEEN   -> isDateBetween(fieldVal, ruleVal, clause.getValueTo());
        };
    }

    // ── Action application ────────────────────────────────────────────

    private void applyActions(List<RuleDsl.RuleAction> actions,
                               Set<String> hidden, Set<String> disabled,
                               Set<String> required, Set<String> hiddenSec,
                               List<String> blocking, Map<String, Object> computed,
                               Map<String, Object> data) {
        for (RuleDsl.RuleAction a : actions) {
            String t = a.getTarget();
            switch (a.getType()) {
                case HIDE          -> hidden.add(t);
                case SHOW          -> hidden.remove(t);
                case TOGGLE        -> { if (hidden.contains(t)) hidden.remove(t); else hidden.add(t); }
                case REQUIRE       -> required.add(t);
                case UNREQUIRE     -> required.remove(t);
                case DISABLE       -> disabled.add(t);
                case ENABLE        -> disabled.remove(t);
                case HIDE_SECTION  -> hiddenSec.add(t);
                case SHOW_SECTION  -> hiddenSec.remove(t);
                case BLOCK_SUBMIT  -> { if (a.getValue() != null) blocking.add(a.getValue().toString()); }
                case SET_VALUE     -> computed.put(t, a.getValue());
                case CLEAR         -> computed.put(t, null);
                case COPY_FROM     -> { if (a.getValue() != null) computed.put(t, data.get(a.getValue().toString())); }
                default            -> log.debug("Action {} not handled server-side", a.getType());
            }
        }
    }

    // ── Operator helpers ──────────────────────────────────────────────

    private boolean equals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.toString().equalsIgnoreCase(b.toString());
    }

    private boolean isEmpty(Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isBlank();
        if (v instanceof Collection<?> c) return c.isEmpty();
        return false;
    }

    private boolean containsStr(Object v, Object sub) {
        if (v == null || sub == null) return false;
        return v.toString().toLowerCase().contains(sub.toString().toLowerCase());
    }

    private boolean startsWith(Object v, Object pre) {
        if (v == null || pre == null) return false;
        return v.toString().startsWith(pre.toString());
    }

    private boolean endsWith(Object v, Object suf) {
        if (v == null || suf == null) return false;
        return v.toString().endsWith(suf.toString());
    }

    @SuppressWarnings("unchecked")
    private boolean isIn(Object v, Object list) {
        if (v == null || list == null) return false;
        if (list instanceof List<?> l) return l.stream().anyMatch(item -> equals(v, item));
        return equals(v, list);
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    private int compare(Object a, Object b) {
        return Double.compare(toDouble(a), toDouble(b));
    }

    private boolean isBetween(Object v, Object lo, Object hi) {
        return compare(v, lo) >= 0 && compare(v, hi) <= 0;
    }

    private LocalDate parseDate(Object v) {
        if (v == null) return null;
        String s = v.toString();
        if (s.equalsIgnoreCase("TODAY")) return LocalDate.now();
        if (s.startsWith("TODAY+")) return LocalDate.now().plusDays(Long.parseLong(s.substring(6)));
        if (s.startsWith("TODAY-")) return LocalDate.now().minusDays(Long.parseLong(s.substring(6)));
        return LocalDate.parse(s, DateTimeFormatter.ISO_DATE);
    }

    private int compareDate(Object v, Object rule) {
        LocalDate a = parseDate(v), b = parseDate(rule);
        if (a == null || b == null) return 0;
        return a.compareTo(b);
    }

    private boolean isDateBetween(Object v, Object lo, Object hi) {
        return compareDate(v, lo) >= 0 && compareDate(v, hi) <= 0;
    }

    private RuleEvaluationResult empty() {
        return new RuleEvaluationResult(Set.of(), Set.of(), Set.of(), Set.of(), List.of(), Map.of());
    }
}
