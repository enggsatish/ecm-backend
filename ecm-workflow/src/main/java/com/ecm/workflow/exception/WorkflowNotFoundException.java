package com.ecm.workflow.exception;

import com.ecm.common.exception.ResourceNotFoundException;

public class WorkflowNotFoundException extends ResourceNotFoundException {
    public WorkflowNotFoundException(String resourceType, Object resourceId) {
        super(resourceType, resourceId);
    }
}
