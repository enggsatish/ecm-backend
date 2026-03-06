package com.ecm.eforms.model.schema;

import lombok.Data;
import java.util.List;

/**
 * Rule DSL — the four building blocks stored in FormSchema.globalRules
 * and FormField.rules as JSONB fragments.
 *
 * A RuleSet defines:
 *   WHEN  trigger  (ON_CHANGE | ON_LOAD | ON_SUBMIT)
 *   IF    condition (recursive AND/OR tree of Clauses)
 *   THEN  actions[]
 *   ELSE  elseActions[]  (optional)
 */
public class RuleDsl {

    /** Leaf condition: field operator value */
    @Data
    public static class Clause {
        private String       field;     // field key being tested
        private RuleOperator operator;
        private Object       value;     // scalar, list, or date expression
        private Object       valueTo;   // used for BETWEEN / DATE_BETWEEN
    }

    /** Recursive AND/OR condition node */
    @Data
    public static class Condition {
        private String          logic         = "AND"; // "AND" | "OR"
        private List<Clause>    clauses;
        private List<Condition> subConditions; // recursive nesting
    }

    /** A single action produced when a condition matches */
    @Data
    public static class RuleAction {
        private RuleActionType type;
        private String         target;  // field key or section id
        private Object         value;   // used by SET_VALUE, SHOW_MESSAGE, SET_PRIORITY
    }

    /** Complete rule: trigger + condition + actions */
    @Data
    public static class RuleSet {
        private String         id;           // unique within the form
        private String         trigger;      // ON_CHANGE | ON_LOAD | ON_SUBMIT
        private Condition      condition;
        private List<RuleAction> actions;
        private List<RuleAction> elseActions;
    }
}
