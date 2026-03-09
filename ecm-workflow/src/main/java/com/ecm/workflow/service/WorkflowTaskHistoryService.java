package com.ecm.workflow.service;

import com.ecm.workflow.dto.WorkflowDtos.TaskHistoryDto;
import com.ecm.workflow.model.entity.WorkflowTaskHistory;
import com.ecm.workflow.repository.WorkflowTaskHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Writes an immutable audit row to workflow_task_history on every task lifecycle event.
 *
 * Callers (WorkflowQueueController, EcmTaskService) invoke record() after completing the
 * primary Flowable operation. Failures here are logged but must not roll back the primary
 * operation — use @Transactional(propagation = REQUIRES_NEW) if strict audit isolation
 * is needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTaskHistoryService {

    private final WorkflowTaskHistoryRepository repo;

    @Transactional
    public WorkflowTaskHistory record(
            String taskId,
            String processInstanceId,
            String action,
            String actorSubject,
            String actorEmail,
            String comment,
            UUID documentId) {

        WorkflowTaskHistory h = WorkflowTaskHistory.builder()
                .taskId(taskId)
                .processInstanceId(processInstanceId)
                .action(action)
                .actorSubject(actorSubject)
                .actorEmail(actorEmail)
                .comment(comment)
                .documentId(documentId)
                .build();

        WorkflowTaskHistory saved = repo.save(h);
        log.info("[TaskHistory] action={} taskId={} actor={}", action, taskId, actorSubject);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<TaskHistoryDto> getHistoryForTask(String taskId) {
        return repo.findByTaskIdOrderByCreatedAtAsc(taskId)
                .stream()
                .map(h -> new TaskHistoryDto(
                        h.getId(),
                        h.getTaskId(),
                        h.getProcessInstanceId(),
                        h.getAction(),
                        h.getActorSubject(),
                        h.getActorEmail(),
                        h.getComment(),
                        h.getCreatedAt()))
                .toList();
    }
}
