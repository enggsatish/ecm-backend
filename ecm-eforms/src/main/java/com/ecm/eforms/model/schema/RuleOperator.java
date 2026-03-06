package com.ecm.eforms.model.schema;

/** Comparison operators used in the rule DSL condition clauses. */
public enum RuleOperator {
    EQUALS, NOT_EQUALS,
    CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH,
    GREATER_THAN, LESS_THAN, GREATER_OR_EQUAL, LESS_OR_EQUAL,
    BETWEEN,
    IS_EMPTY, IS_NOT_EMPTY,
    IN, NOT_IN,
    BEFORE_DATE, AFTER_DATE, DATE_BETWEEN
}
