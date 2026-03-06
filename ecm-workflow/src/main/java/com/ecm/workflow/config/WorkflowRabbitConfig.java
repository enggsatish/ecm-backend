package com.ecm.workflow.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for ecm-workflow.
 *
 * Consumes from:
 *   Exchange: ecm.documents  (declared by ecm-document — passive bind only)
 *   Queue:    ecm.workflow.triggers
 *   Routing:  document.ocr.request  ← same event ecm-document publishes
 *
 * Publishes to:
 *   Exchange: ecm.workflow  (owned by this service)
 *   Routing keys:
 *     workflow.task.assigned  → ecm-notification will consume this
 *     workflow.completed      → ecm-notification will consume this
 *
 * Design note: we bind to ecm-document's EXISTING exchange rather than
 * requiring ecm-document to know about workflow. Loose coupling — ecm-document
 * publishes one event; multiple consumers (OCR, workflow) bind independently.
 */
@Configuration
public class WorkflowRabbitConfig {

    // ── Exchange names ─────────────────────────────────────────────────────

    /** Owned by ecm-document — we bind to it passively */
    public static final String DOCUMENT_EXCHANGE = "ecm.documents";

    /** Owned by this service — workflow events outbound */
    public static final String WORKFLOW_EXCHANGE  = "ecm.workflow";

    // ── Routing keys ───────────────────────────────────────────────────────

    /** Published by ecm-document; consumed here to check for workflow triggers */
    public static final String DOCUMENT_UPLOADED_ROUTING_KEY = "document.ocr.request";

    /** Published by this service when a task is assigned */
    public static final String TASK_ASSIGNED_ROUTING_KEY     = "workflow.task.assigned";

    /** Published by this service when a workflow completes */
    public static final String WORKFLOW_COMPLETED_ROUTING_KEY = "workflow.completed";

    // ── Queues ─────────────────────────────────────────────────────────────

    public static final String WORKFLOW_TRIGGER_QUEUE = "ecm.workflow.triggers";

    // ── Beans ──────────────────────────────────────────────────────────────
// In WorkflowRabbitConfig.java — add if missing
    public static final String DOCUMENT_UPLOADED_QUEUE = "ecm.workflow.document.uploaded";

    @Bean
    public Queue documentUploadedQueue() {
        return QueueBuilder.durable(DOCUMENT_UPLOADED_QUEUE)
                .withArgument("x-dead-letter-exchange", "ecm.workflow.dlx")
                .build();
    }

    @Bean
    public Binding documentUploadedBinding() {
        return BindingBuilder
                .bind(documentUploadedQueue())
                .to(workflowExchange())
                .with("document.uploaded");
    }

    @Bean
    public TopicExchange workflowExchange() {
        return ExchangeBuilder.topicExchange(WORKFLOW_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue workflowTriggerQueue() {
        // Dead-letter queue for failed message processing
        return QueueBuilder.durable(WORKFLOW_TRIGGER_QUEUE)
                .withArgument("x-dead-letter-exchange", "ecm.dlx")
                .build();
    }

    /**
     * Bind our trigger queue to ecm-document's exchange.
     * When a document is uploaded, we receive a copy of the OCR event
     * and check if the document's category has a workflow mapping.
     */
    @Bean
    public Binding workflowTriggerBinding(Queue workflowTriggerQueue) {
        // Declare the document exchange as a reference (not the owner)
        TopicExchange documentExchangeRef =
                ExchangeBuilder.topicExchange(DOCUMENT_EXCHANGE).durable(true).build();
        return BindingBuilder
                .bind(workflowTriggerQueue)
                .to(documentExchangeRef)
                .with(DOCUMENT_UPLOADED_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(cf);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
