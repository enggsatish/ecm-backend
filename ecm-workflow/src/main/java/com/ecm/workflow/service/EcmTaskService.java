package com.ecm.workflow.service;

import com.ecm.common.exception.ResourceNotFoundException;
import com.ecm.workflow.dto.WorkflowDtos.TaskActionRequest;
import com.ecm.workflow.dto.WorkflowDtos.WorkflowTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Wraps Flowable's TaskService for ECM-specific task operations.
 *
 * Key concepts:
 *  - Candidate group: a Flowable string (role name or "group:N") that can see/claim the task
 *  - Assignee: the specific user who claimed (locked) the task
 *  - Claim: moves task from open pool to a specific user's inbox
 *  - Complete: submits the decision (APPROVED/REJECTED/etc.) and advances the process
 *
 * Authorization:
 *  - Only users whose role/group matches the task's candidateGroups can claim/complete
 *  - Once claimed, only the assignee can complete (unless admin)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcmTaskService {

    private final org.flowable.engine.TaskService flowableTaskService;
    private final HistoryService                  historyService;
    private final WorkflowInstanceService         workflowInstanceService;

    // ── Query tasks ───────────────────────────────────────────────────────

    /**
     * All unclaimed tasks visible to any of the given candidate groups.
     * Used for the "pending pool" view (backoffice inbox).
     */
    public List<WorkflowTaskDto> getPendingTasksForGroups(List<String> candidateGroups) {
        if (candidateGroups == null || candidateGroups.isEmpty()) return List.of();

        List<Task> tasks = flowableTaskService.createTaskQuery()
                .taskCandidateGroupIn(candidateGroups)
                .taskUnassigned()         // unclaimed only
                .orderByTaskCreateTime().desc()
                .list();

        return tasks.stream().map(this::toDto).toList();
    }

    /**
     * All tasks assigned to a specific user (claimed tasks).
     */
    public List<WorkflowTaskDto> getMyTasks(String userSubject) {
        List<Task> tasks = flowableTaskService.createTaskQuery()
                .taskAssignee(userSubject)
                .orderByTaskCreateTime().desc()
                .list();
        return tasks.stream().map(this::toDto).toList();
    }

    /**
     * All tasks assigned TO the user OR in one of the candidate groups (full inbox view).
     */
    public List<WorkflowTaskDto> getMyInbox(String userSubject, List<String> candidateGroups) {
        // Claimed tasks (mine)
        List<Task> mine = flowableTaskService.createTaskQuery()
                .taskAssignee(userSubject)
                .orderByTaskCreateTime().desc()
                .list();

        // Pool tasks available to my groups
        List<Task> pool = candidateGroups.isEmpty() ? List.of() :
                flowableTaskService.createTaskQuery()
                        .taskCandidateGroupIn(candidateGroups)
                        .taskUnassigned()
                        .orderByTaskCreateTime().desc()
                        .list();

        // Merge, de-duplicate by task ID
        Map<String, Task> merged = new LinkedHashMap<>();
        mine.forEach(t -> merged.put(t.getId(), t));
        pool.forEach(t -> merged.putIfAbsent(t.getId(), t));

        return merged.values().stream().map(this::toDto).toList();
    }

    /**
     * Get a single task by ID (throws if not found).
     */
    public WorkflowTaskDto getTask(String taskId) {
        Task task = requireTask(taskId);
        return toDto(task);
    }

    // ── Task actions ──────────────────────────────────────────────────────

    /**
     * Claim a task from the pool — locks it to this user.
     * User must be in one of the task's candidate groups.
     */
    @Transactional
    public WorkflowTaskDto claim(String taskId, String userSubject, List<String> userGroups) {
        Task task = requireTask(taskId);

        if (task.getAssignee() != null) {
            throw new IllegalStateException(
                    "Task is already claimed by: " + task.getAssignee());
        }

        assertUserCanActOnTask(task, userSubject, userGroups);

        flowableTaskService.claim(taskId, userSubject);
        log.info("Task claimed: taskId={}, by={}", taskId, userSubject);

        return toDto(requireTask(taskId));
    }

    /**
     * Unclaim a task — returns it to the pool.
     * Only the current assignee can unclaim.
     */
    @Transactional
    public void unclaim(String taskId, String userSubject) {
        Task task = requireTask(taskId);
        if (!userSubject.equals(task.getAssignee())) {
            throw new AccessDeniedException("Only the task assignee can unclaim it");
        }
        flowableTaskService.unclaim(taskId);
        log.info("Task unclaimed: taskId={}, by={}", taskId, userSubject);
    }

    /**
     * Approve a document — completes the task with decision=APPROVED.
     */
    @Transactional
    public void approve(String taskId, TaskActionRequest req,
                        String userSubject, List<String> userGroups) {
        complete(taskId, "APPROVED", req.comment(), userSubject, userGroups);
    }

    /**
     * Reject a document — completes the task with decision=REJECTED.
     * Comment is required for rejection.
     */
    @Transactional
    public void reject(String taskId, TaskActionRequest req,
                       String userSubject, List<String> userGroups) {
        if (req.comment() == null || req.comment().isBlank()) {
            throw new IllegalArgumentException("A comment is required when rejecting a document");
        }
        complete(taskId, "REJECTED", req.comment(), userSubject, userGroups);
    }

    /**
     * Request additional information from the submitter.
     * Comment is required (explains what info is needed).
     * Also updates WorkflowInstanceRecord to INFO_REQUESTED so the submitter's
     * dashboard highlights the pending action.
     */
    @Transactional
    public void requestInfo(String taskId, TaskActionRequest req,
                            String userSubject, List<String> userGroups) {
        if (req.comment() == null || req.comment().isBlank()) {
            throw new IllegalArgumentException(
                    "A comment is required when requesting additional information");
        }
        Task task = requireTask(taskId);
        String processInstanceId = task.getProcessInstanceId();
        complete(taskId, "REQUEST_INFO", req.comment(), userSubject, userGroups);
        // Mark instance so submitter sees it needs attention
        workflowInstanceService.markInfoRequested(processInstanceId);
    }

    /**
     * Pass to specialist (dual-review triage only).
     */
    @Transactional
    public void pass(String taskId, TaskActionRequest req,
                     String userSubject, List<String> userGroups) {
        complete(taskId, "PASS", req.comment(), userSubject, userGroups);
    }

    /**
     * Submitter provides the additional information requested by the reviewer.
     * Completes the "Provide Additional Information" task assigned to the submitter,
     * which routes the process back to the reviewer queue.
     *
     * Only the original submitter (task assignee) may call this.
     */
    @Transactional
    public WorkflowTaskDto provideInfo(String taskId, String comment, String userSubject) {
        Task task = requireTask(taskId);

        if (!userSubject.equals(task.getAssignee())) {
            throw new AccessDeniedException(
                    "Only the assigned submitter can provide information on this task");
        }

        String processInstanceId = task.getProcessInstanceId();

        Map<String, Object> vars = new HashMap<>();
        vars.put("decision",        "INFO_PROVIDED");
        vars.put("comment",         comment != null ? comment : "");
        vars.put("infoProvidedBy",  userSubject);

        flowableTaskService.complete(taskId, vars);
        log.info("Info provided: taskId={}, by={}", taskId, userSubject);

        // Transition instance back to ACTIVE — reviewer queue will pick it up
        workflowInstanceService.markInfoProvided(processInstanceId);

        // Return a summary DTO (task is now completed, so return a placeholder)
        return new WorkflowTaskDto(taskId, "Provide Additional Information",
                "Information provided", processInstanceId,
                userSubject, List.of(), "", "", null, "COMPLETED");
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void complete(String taskId, String decision, String comment,
                          String userSubject, List<String> userGroups) {
        Task task = requireTask(taskId);
        assertUserCanActOnTask(task, userSubject, userGroups);

        // Auto-claim if not already claimed (convenience — reviewer doesn't have to claim first)
        if (task.getAssignee() == null) {
            flowableTaskService.claim(taskId, userSubject);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("decision",   decision);
        vars.put("comment",    comment != null ? comment : "");
        vars.put("reviewedBy", userSubject);

        flowableTaskService.complete(taskId, vars);
        log.info("Task completed: taskId={}, decision={}, by={}", taskId, decision, userSubject);
    }

    /**
     * Verify the user can act on this task:
     *   - Either they are the assignee
     *   - Or they are in one of the task's candidate groups
     */
    private void assertUserCanActOnTask(Task task, String userSubject, List<String> userGroups) {
        if (userSubject.equals(task.getAssignee())) return;

        // Check candidate groups
        List<String> taskCandidateGroups = flowableTaskService.getIdentityLinksForTask(task.getId())
                .stream()
                .filter(link -> "candidate".equals(link.getType()) && link.getGroupId() != null)
                .map(link -> link.getGroupId())
                .toList();

        boolean authorised = userGroups.stream()
                .anyMatch(taskCandidateGroups::contains);

        if (!authorised) {
            throw new AccessDeniedException(
                    "You are not in the candidate group for this task. " +
                    "Required: " + taskCandidateGroups + ", Your groups: " + userGroups);
        }
    }

    private Task requireTask(String taskId) {
        Task task = flowableTaskService.createTaskQuery()
                .taskId(taskId)
                .singleResult();
        if (task == null) {
            throw new ResourceNotFoundException("Task", taskId);
        }
        return task;
    }

    private WorkflowTaskDto toDto(Task task) {
        Map<String, Object> vars = flowableTaskService.getVariables(task.getId());

        List<String> groups = flowableTaskService.getIdentityLinksForTask(task.getId())
                .stream()
                .filter(l -> "candidate".equals(l.getType()) && l.getGroupId() != null)
                .map(l -> l.getGroupId())
                .toList();

        String status = task.getAssignee() != null ? "CLAIMED" : "PENDING";

        return new WorkflowTaskDto(
                task.getId(),
                task.getName(),
                task.getDescription(),
                task.getProcessInstanceId(),
                task.getAssignee(),
                groups,
                vars.getOrDefault("documentId",   "").toString(),
                vars.getOrDefault("documentName", "").toString(),
                task.getCreateTime() != null
                        ? task.getCreateTime().toInstant()
                            .atOffset(java.time.ZoneOffset.UTC)
                        : null,
                status
        );
    }
}
