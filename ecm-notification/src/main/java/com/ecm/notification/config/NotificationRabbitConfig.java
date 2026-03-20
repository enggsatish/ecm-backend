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
 *   ecm.workflow    → workflow.task.assigned  (notify reviewer group)
 *   ecm.workflow    → workflow.completed      (notify submitter)
 *   ecm.notifications → notification.email    (from BPMN NotificationDelegate)
 *   ecm.eforms     → form.reviewed           (notify submitter of approval/rejection)
 */
@Configuration
public class NotificationRabbitConfig {

    public static final String WORKFLOW_EXCHANGE      = "ecm.workflow";
    public static final String NOTIFICATIONS_EXCHANGE = "ecm.notifications";
    public static final String EFORMS_EXCHANGE        = "ecm.eforms";

    public static final String Q_TASK_ASSIGNED       = "ecm.notification.task.assigned";
    public static final String Q_WORKFLOW_COMPLETED  = "ecm.notification.workflow.completed";
    public static final String Q_NOTIFICATION_EMAIL  = "ecm.notification.email";
    public static final String Q_FORM_REVIEWED       = "ecm.notification.form.reviewed";

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

    // ── Queues ───────────────────────────────────────────────────────────────

    @Bean Queue taskAssignedQueue()      { return QueueBuilder.durable(Q_TASK_ASSIGNED).build(); }
    @Bean Queue workflowCompletedQueue() { return QueueBuilder.durable(Q_WORKFLOW_COMPLETED).build(); }
    @Bean Queue notificationEmailQueue() { return QueueBuilder.durable(Q_NOTIFICATION_EMAIL).build(); }
    @Bean Queue formReviewedQueue()      { return QueueBuilder.durable(Q_FORM_REVIEWED).build(); }

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
