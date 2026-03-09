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
 * ── Exchanges consumed ────────────────────────────────────────────────────────
 *
 *  ecm.documents  (owned by ecm-document)
 *    Queue: ecm.workflow.triggers
 *    RK:    document.ocr.request  ← document uploaded → check category for workflow trigger
 *
 *  ecm.eforms  (owned by ecm-eforms)  ← FIX: previously missing
 *    Queue: ecm.workflow.form.submitted
 *    RK:    form.submitted  ← form filled and submitted → start workflow instance
 *
 * ── Exchange published ────────────────────────────────────────────────────────
 *
 *  ecm.workflow  (owned by this service)
 *    RK: workflow.task.assigned  → ecm-notification emails reviewer group
 *    RK: workflow.completed      → ecm-eforms promotes approved form to document
 *
 * ── Dead-letter ───────────────────────────────────────────────────────────────
 *
 *  ecm.workflow.dlx → ecm.workflow.dlq
 *  Both queues above use this DLX to avoid message loss on processing failure.
 */
@Configuration
public class WorkflowRabbitConfig {

    // ── Exchange names ─────────────────────────────────────────────────────

    /** Owned by ecm-document — we bind passively */
    public static final String DOCUMENT_EXCHANGE        = "ecm.documents";

    /** Owned by ecm-eforms — we bind passively */
    public static final String EFORMS_EXCHANGE          = "ecm.eforms";

    /** Owned by this service */
    public static final String WORKFLOW_EXCHANGE        = "ecm.workflow";

    // ── Routing keys inbound ───────────────────────────────────────────────
    public static final String DOCUMENT_UPLOADED_RK = "document.workflow.trigger";
    public static final String FORM_SUBMITTED_RK        = "form.submitted";

    // ── Routing keys outbound ──────────────────────────────────────────────

    // Original names — FlowableListenersConfig references these; must not be renamed
    public static final String TASK_ASSIGNED_ROUTING_KEY      = "workflow.task.assigned";
    public static final String WORKFLOW_COMPLETED_ROUTING_KEY = "workflow.completed";
    // Short aliases used by new code in this module
    public static final String TASK_ASSIGNED_RK    = TASK_ASSIGNED_ROUTING_KEY;
    public static final String WORKFLOW_COMPLETED_RK = WORKFLOW_COMPLETED_ROUTING_KEY;

    // ── Queue names ────────────────────────────────────────────────────────

    public static final String WORKFLOW_TRIGGER_QUEUE       = "ecm.workflow.triggers";
    public static final String DOCUMENT_UPLOADED_QUEUE      = "ecm.workflow.document.uploaded";

    /** New queue — consumes form.submitted events from ecm.eforms exchange */
    public static final String FORM_SUBMITTED_QUEUE         = "ecm.workflow.form.submitted";

    /** Dead-letter */
    public static final String WORKFLOW_DLX                 = "ecm.workflow.dlx";
    public static final String WORKFLOW_DLQ                 = "ecm.workflow.dlq";

    // ── Dead-letter infrastructure ─────────────────────────────────────────

    @Bean
    public DirectExchange workflowDlxExchange() {
        return ExchangeBuilder.directExchange(WORKFLOW_DLX).durable(true).build();
    }

    @Bean
    public Queue workflowDlq() {
        return QueueBuilder.durable(WORKFLOW_DLQ).build();
    }

    @Bean
    public Binding workflowDlqBinding(Queue workflowDlq, DirectExchange workflowDlxExchange) {
        return BindingBuilder.bind(workflowDlq).to(workflowDlxExchange).with(WORKFLOW_DLQ);
    }

    // ── Workflow exchange (outbound) ───────────────────────────────────────

    @Bean
    public TopicExchange workflowExchange() {
        return ExchangeBuilder.topicExchange(WORKFLOW_EXCHANGE).durable(true).build();
    }

    // ── ecm.documents consumer (document uploaded → workflow trigger) ──────
    // IMPORTANT: ecm.workflow.triggers already exists in RabbitMQ with
    // x-dead-letter-exchange=ecm.dlx (the original declaration).
    // We must match that exactly — changing the DLX causes PRECONDITION_FAILED.
    // Do NOT change this to ecm.workflow.dlx without first deleting the queue in RabbitMQ.

    @Bean
    public Queue workflowTriggerQueue() {
        return QueueBuilder.durable(WORKFLOW_TRIGGER_QUEUE)
                .withArgument("x-dead-letter-exchange", "ecm.dlx")   // matches existing queue
                .build();
    }

    @Bean
    public Binding workflowTriggerBinding(Queue workflowTriggerQueue) {
        TopicExchange docExchangeRef =
                ExchangeBuilder.topicExchange(DOCUMENT_EXCHANGE).durable(true).build();
        return BindingBuilder.bind(workflowTriggerQueue).to(docExchangeRef)
                .with(DOCUMENT_UPLOADED_RK);
    }

    @Bean
    public Queue documentUploadedQueue() {
        return QueueBuilder.durable(DOCUMENT_UPLOADED_QUEUE)
                .deadLetterExchange(WORKFLOW_DLX)
                .deadLetterRoutingKey(WORKFLOW_DLQ)
                .build();
    }

//    @Bean
//    public Binding documentUploadedBinding(Queue documentUploadedQueue,
//                                           TopicExchange workflowExchange) {
//        return BindingBuilder.bind(documentUploadedQueue).to(workflowExchange)
//                .with("document.uploaded");
//    }

    @Bean
    public Binding documentUploadedBinding(Queue documentUploadedQueue) {
        // Passive reference — ecm-document owns ecm.documents, re-declare is a no-op.
        TopicExchange documentExchangeRef =
                ExchangeBuilder.topicExchange(DOCUMENT_EXCHANGE).durable(true).build();
        return BindingBuilder.bind(documentUploadedQueue)
                .to(documentExchangeRef)
                .with("document.workflow.trigger");   // matches Fix 2A WORKFLOW_TRIGGER_ROUTING_KEY
    }

    // ── ecm.eforms consumer (form submitted → start workflow) ─────────────
    // FIX: This queue + binding was MISSING. Without it, submitted forms never
    // triggered a workflow instance, leaving FormSubmission stuck in SUBMITTED
    // status and no reviewer task appearing in the inbox.

    @Bean
    public Queue formSubmittedQueue() {
        return QueueBuilder.durable(FORM_SUBMITTED_QUEUE)
                .deadLetterExchange(WORKFLOW_DLX)
                .deadLetterRoutingKey(WORKFLOW_DLQ)
                .build();
    }

    @Bean
    public Binding formSubmittedBinding(Queue formSubmittedQueue) {
        // Passive reference to ecm.eforms exchange (owned by ecm-eforms).
        // Both modules declare it identically — RabbitMQ treats re-declares as no-ops.
        TopicExchange eformsExchangeRef =
                ExchangeBuilder.topicExchange(EFORMS_EXCHANGE).durable(true).build();
        return BindingBuilder.bind(formSubmittedQueue).to(eformsExchangeRef)
                .with(FORM_SUBMITTED_RK);
    }

    // ── Converters ─────────────────────────────────────────────────────────

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