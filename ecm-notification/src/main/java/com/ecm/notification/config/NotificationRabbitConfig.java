package com.ecm.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for ecm-notification.
 *
 * Consumes from:
 *   ecm.workflow      → workflow.task.assigned   (notify reviewer group)
 *   ecm.workflow      → workflow.completed       (notify submitter)
 *   ecm.notifications → notification.email       (from BPMN NotificationDelegate)
 *   ecm.eforms        → form.reviewed            (notify submitter of approval/rejection)
 *   ecm.admin         → case.otp.requested       (send OTP immediately)
 *   ecm.admin         → case.participant.added    (send invitation email)
 *   ecm.admin         → user.invited              (send platform invitation)
 *   ecm.admin         → case.assigned             (notify assignee)
 *   ecm.documents     → document.classified       (notify uploader)
 *   ecm.documents     → document.classification.stale (alert reviewers)
 *   ecm.batch         → batch.completed           (notify batch creator)
 *   ecm.batch         → batch.failed              (notify batch creator)
 */
@Configuration
public class NotificationRabbitConfig {

    public static final String WORKFLOW_EXCHANGE      = "ecm.workflow";
    public static final String NOTIFICATIONS_EXCHANGE = "ecm.notifications";
    public static final String EFORMS_EXCHANGE        = "ecm.eforms";
    public static final String ADMIN_EXCHANGE         = "ecm.admin";
    public static final String DOCUMENTS_EXCHANGE     = "ecm.documents";
    public static final String BATCH_EXCHANGE         = "ecm.batch";

    public static final String Q_TASK_ASSIGNED       = "ecm.notification.task.assigned";
    public static final String Q_WORKFLOW_COMPLETED  = "ecm.notification.workflow.completed";
    public static final String Q_NOTIFICATION_EMAIL  = "ecm.notification.email";
    public static final String Q_FORM_REVIEWED       = "ecm.notification.form.reviewed";
    public static final String Q_CASE_OTP            = "ecm.notification.case.otp";
    public static final String Q_CASE_INVITE         = "ecm.notification.case.invite";
    public static final String Q_USER_INVITED        = "ecm.notification.user.invited";
    public static final String Q_CASE_ASSIGNED       = "ecm.notification.case.assigned";
    public static final String Q_DOC_CLASSIFIED      = "ecm.notification.document.classified";
    public static final String Q_DOC_STALE           = "ecm.notification.document.stale";
    public static final String Q_BATCH_COMPLETED     = "ecm.notification.batch.completed";
    public static final String Q_BATCH_FAILED        = "ecm.notification.batch.failed";

    // ── Exchange declarations (idempotent — owned by other modules) ──────────

    @Bean TopicExchange workflowExchange() {
        return ExchangeBuilder.topicExchange(WORKFLOW_EXCHANGE).durable(true).build();
    }

    @Bean TopicExchange notificationsExchange() {
        return ExchangeBuilder.topicExchange(NOTIFICATIONS_EXCHANGE).durable(true).build();
    }

    @Bean TopicExchange eformsExchange() {
        return ExchangeBuilder.topicExchange(EFORMS_EXCHANGE).durable(true).build();
    }

    @Bean TopicExchange adminExchange() {
        return ExchangeBuilder.topicExchange(ADMIN_EXCHANGE).durable(true).build();
    }

    @Bean TopicExchange documentsExchange() {
        return ExchangeBuilder.topicExchange(DOCUMENTS_EXCHANGE).durable(true).build();
    }

    @Bean TopicExchange batchExchange() {
        return ExchangeBuilder.topicExchange(BATCH_EXCHANGE).durable(true).build();
    }

    // ── Queues ───────────────────────────────────────────────────────────────

    @Bean Queue taskAssignedQueue()      { return QueueBuilder.durable(Q_TASK_ASSIGNED).build(); }
    @Bean Queue workflowCompletedQueue() { return QueueBuilder.durable(Q_WORKFLOW_COMPLETED).build(); }
    @Bean Queue notificationEmailQueue() { return QueueBuilder.durable(Q_NOTIFICATION_EMAIL).build(); }
    @Bean Queue formReviewedQueue()      { return QueueBuilder.durable(Q_FORM_REVIEWED).build(); }
    @Bean Queue caseOtpQueue()           { return QueueBuilder.durable(Q_CASE_OTP).build(); }
    @Bean Queue caseInviteQueue()        { return QueueBuilder.durable(Q_CASE_INVITE).build(); }
    @Bean Queue userInvitedQueue()       { return QueueBuilder.durable(Q_USER_INVITED).build(); }
    @Bean Queue caseAssignedQueue()      { return QueueBuilder.durable(Q_CASE_ASSIGNED).build(); }
    @Bean Queue docClassifiedQueue()     { return QueueBuilder.durable(Q_DOC_CLASSIFIED).build(); }
    @Bean Queue docStaleQueue()          { return QueueBuilder.durable(Q_DOC_STALE).build(); }
    @Bean Queue batchCompletedQueue()    { return QueueBuilder.durable(Q_BATCH_COMPLETED).build(); }
    @Bean Queue batchFailedQueue()       { return QueueBuilder.durable(Q_BATCH_FAILED).build(); }

    // ── Bindings ─────────────────────────────────────────────────────────────

    @Bean Binding taskAssignedBinding(Queue taskAssignedQueue, TopicExchange workflowExchange) {
        return BindingBuilder.bind(taskAssignedQueue).to(workflowExchange).with("workflow.task.assigned");
    }

    @Bean Binding workflowCompletedBinding(Queue workflowCompletedQueue, TopicExchange workflowExchange) {
        return BindingBuilder.bind(workflowCompletedQueue).to(workflowExchange).with("workflow.completed");
    }

    @Bean Binding notificationEmailBinding(Queue notificationEmailQueue, TopicExchange notificationsExchange) {
        return BindingBuilder.bind(notificationEmailQueue).to(notificationsExchange).with("notification.email");
    }

    @Bean Binding formReviewedBinding(Queue formReviewedQueue, TopicExchange eformsExchange) {
        return BindingBuilder.bind(formReviewedQueue).to(eformsExchange).with("form.reviewed");
    }

    @Bean Binding caseOtpBinding(Queue caseOtpQueue, TopicExchange adminExchange) {
        return BindingBuilder.bind(caseOtpQueue).to(adminExchange).with("case.otp.requested");
    }

    @Bean Binding caseInviteBinding(Queue caseInviteQueue, TopicExchange adminExchange) {
        return BindingBuilder.bind(caseInviteQueue).to(adminExchange).with("case.participant.added");
    }

    @Bean Binding userInvitedBinding(Queue userInvitedQueue, TopicExchange adminExchange) {
        return BindingBuilder.bind(userInvitedQueue).to(adminExchange).with("user.invited");
    }

    @Bean Binding caseAssignedBinding(Queue caseAssignedQueue, TopicExchange adminExchange) {
        return BindingBuilder.bind(caseAssignedQueue).to(adminExchange).with("case.assigned");
    }

    // ── Document exchange bindings ─────────────────────────────────────────

    @Bean Binding docClassifiedBinding(Queue docClassifiedQueue, TopicExchange documentsExchange) {
        return BindingBuilder.bind(docClassifiedQueue).to(documentsExchange).with("document.classified");
    }

    @Bean Binding docStaleBinding(Queue docStaleQueue, TopicExchange documentsExchange) {
        return BindingBuilder.bind(docStaleQueue).to(documentsExchange).with("document.classification.stale");
    }

    // ── Batch exchange bindings ────────────────────────────────────────────

    @Bean Binding batchCompletedBinding(Queue batchCompletedQueue, TopicExchange batchExchange) {
        return BindingBuilder.bind(batchCompletedQueue).to(batchExchange).with("batch.completed");
    }

    @Bean Binding batchFailedBinding(Queue batchFailedQueue, TopicExchange batchExchange) {
        return BindingBuilder.bind(batchFailedQueue).to(batchExchange).with("batch.failed");
    }

    // ── Converters ───────────────────────────────────────────────────────────

    @Bean MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean RabbitTemplate rabbitTemplate(ConnectionFactory cf, MessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(cf);
        template.setMessageConverter(converter);
        return template;
    }
}
