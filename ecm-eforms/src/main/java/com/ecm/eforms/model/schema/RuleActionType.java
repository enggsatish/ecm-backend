package com.ecm.eforms.model.schema;

/** Actions that a rule can execute on target fields or sections. */
public enum RuleActionType {
    // Visibility
    SHOW, HIDE, TOGGLE,
    // Required
    REQUIRE, UNREQUIRE,
    // Interaction
    DISABLE, ENABLE, READONLY,
    // Value
    SET_VALUE, CLEAR, COPY_FROM,
    // Section
    SHOW_SECTION, HIDE_SECTION, JUMP_TO_SECTION,
    // Feedback
    SHOW_MESSAGE, BLOCK_SUBMIT,
    // Metadata
    SET_PRIORITY
}
