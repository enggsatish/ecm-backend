package com.ecm.admin.controller;

import com.ecm.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Lightweight aggregation endpoint for sidebar badge counts.
 * Returns all queue counts relevant to the current user in a single call.
 *
 * Uses JdbcTemplate for cross-schema reads (Flowable ACT_RU_* tables,
 * ecm_core.cases, ecm_forms.form_submissions).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final JdbcTemplate jdbc;

    public record QueueCounts(
            int reviewQueueUnclaimed,   // workflow tasks in user's group, not yet claimed
            int reviewQueueMine,        // workflow tasks claimed by user
            int casesAssignedToGroup,   // cases assigned to user's group, not yet claimed
            int casesAssignedToMe,      // cases assigned directly to user
            int formsToReview           // form submissions pending review
    ) {}

    @GetMapping("/counts")
    public ResponseEntity<ApiResponse<QueueCounts>> getCounts(
            @AuthenticationPrincipal Jwt jwt) {

        String userSub = jwt.getSubject();
        String userEmail = jwt.getClaimAsString("email");

        // Resolve user's roles from Spring Security context
        Collection<? extends GrantedAuthority> authorities =
                SecurityContextHolder.getContext().getAuthentication().getAuthorities();

        List<String> roles = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_ECM_") || a.startsWith("ECM_"))
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .toList();

        // 1. Unclaimed workflow tasks for user's groups
        int reviewUnclaimed = 0;
        if (!roles.isEmpty()) {
            try {
                String placeholders = String.join(",", roles.stream().map(r -> "?").toList());
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT t.id_) FROM public.act_ru_task t " +
                        "JOIN public.act_ru_identitylink il ON il.task_id_ = t.id_ " +
                        "WHERE t.assignee_ IS NULL AND il.type_ = 'candidate' AND il.group_id_ IN (" + placeholders + ")",
                        Integer.class,
                        roles.toArray());
                reviewUnclaimed = count != null ? count : 0;
            } catch (Exception e) {
                log.debug("Failed to count unclaimed tasks: {}", e.getMessage());
            }
        }

        // 2. Tasks claimed by this user
        int reviewMine = 0;
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM public.act_ru_task WHERE assignee_ = ?",
                    Integer.class, userSub);
            reviewMine = count != null ? count : 0;
        } catch (Exception e) {
            log.debug("Failed to count my tasks: {}", e.getMessage());
        }

        // 3. Cases assigned to user's group (not yet claimed)
        int casesGroup = 0;
        if (!roles.isEmpty()) {
            try {
                String placeholders = String.join(",", roles.stream().map(r -> "?").toList());
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ecm_core.cases " +
                        "WHERE assigned_to_group IN (" + placeholders + ") " +
                        "AND claimed_by IS NULL " +
                        "AND status NOT IN ('COMPLETED', 'APPROVED', 'REJECTED', 'CANCELLED')",
                        Integer.class,
                        roles.toArray());
                casesGroup = count != null ? count : 0;
            } catch (Exception e) {
                log.debug("Failed to count group cases: {}", e.getMessage());
            }
        }

        // 4. Cases assigned directly to this user
        int casesMe = 0;
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ecm_core.cases " +
                    "WHERE assigned_to = ? AND status NOT IN ('COMPLETED', 'APPROVED', 'REJECTED', 'CANCELLED')",
                    Integer.class, userSub);
            if (count == null || count == 0) {
                // Try by email
                count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ecm_core.cases " +
                        "WHERE assigned_to = ? AND status NOT IN ('COMPLETED', 'APPROVED', 'REJECTED', 'CANCELLED')",
                        Integer.class, userEmail);
            }
            casesMe = count != null ? count : 0;
        } catch (Exception e) {
            log.debug("Failed to count my cases: {}", e.getMessage());
        }

        // 5. Form submissions pending review (for reviewers/backoffice/admin)
        int formsToReview = 0;
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ecm_forms.form_submissions WHERE status = 'SUBMITTED'",
                    Integer.class);
            formsToReview = count != null ? count : 0;
        } catch (Exception e) {
            log.debug("Failed to count pending submissions: {}", e.getMessage());
        }

        QueueCounts counts = new QueueCounts(
                reviewUnclaimed, reviewMine,
                casesGroup, casesMe,
                formsToReview);

        log.debug("Dashboard counts for {}: {}", userEmail, counts);
        return ResponseEntity.ok(ApiResponse.ok(counts));
    }
}
