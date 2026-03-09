package com.ecm.eforms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for ecm-eforms.
 *
 * ── Published (ecm-eforms owns ecm.eforms exchange) ─────────────────────────
 *
 *   ecm.form.submitted  ← form.submitted   → ecm-workflow starts WorkflowInstance
 *   ecm.form.signed     ← form.signed       → ecm-workflow moves to SIGNED
 *   ecm.form.declined   ← form.sign.declined
 *   ecm.form.reviewed   ← form.reviewed     → ecm-notification emails submitter
 *
 * ── Consumed (ecm-eforms listens to ecm.workflow exchange) ──────────────────
 *
 *   ecm.eforms.workflow.completed  ← workflow.completed  ← FIX: previously missing
 *     When a workflow completes (APPROVED/REJECTED), WorkflowCompletedListener
 *     updates FormSubmission.status and promotes APPROVED submissions to documents.
 *
 * Without this binding, approved forms were stuck in SUBMITTED status forever and
 * no document was ever created in ecm_core.documents.
 */
@Configuration
public class EFormsRabbitConfig {

    // ── Exchanges ─────────────────────────────────────────────────────────

    /** Owned by this service — forms publish here */
    public static final String EFORMS_EXCHANGE   = "ecm.eforms";

    /** Owned by ecm-workflow — we bind passively */
    public static final String WORKFLOW_EXCHANGE = "ecm.workflow";

    // ── Routing keys outbound ─────────────────────────────────────────────

    public static final String RK_SUBMITTED = "form.submitted";
    public static final String RK_SIGNED    = "form.signed";
    public static final String RK_DECLINED  = "form.sign.declined";
    public static final String RK_REVIEWED  = "form.reviewed";

    // ── Routing keys inbound ──────────────────────────────────────────────

    public static final String RK_WORKFLOW_COMPLETED = "workflow.completed";

    // ── Queue names ───────────────────────────────────────────────────────

    public static final String Q_SUBMITTED            = "ecm.form.submitted";
    public static final String Q_SIGNED               = "ecm.form.signed";
    public static final String Q_DECLINED             = "ecm.form.declined";
    public static final String Q_REVIEWED             = "ecm.form.reviewed";
    public static final String Q_DLQ                  = "ecm.eforms.dlq";

    /** New queue — receives workflow.completed from ecm.workflow exchange */
    public static final String Q_WORKFLOW_COMPLETED   = "ecm.eforms.workflow.completed";

    // ── Exchange beans ────────────────────────────────────────────────────

    @Bean
    public TopicExchange eformsExchange() {
        return ExchangeBuilder.topicExchange(EFORMS_EXCHANGE).durable(true).build();
    }

    // ── DLQ ───────────────────────────────────────────────────────────────

    @Bean
    public Queue dlq() {
        return QueueBuilder.durable(Q_DLQ).build();
    }

    // ── Outbound queues (DLX → Q_DLQ on failure) ─────────────────────────

    @Bean public Queue qSubmitted() { return withDlx(Q_SUBMITTED); }
    @Bean public Queue qSigned()    { return withDlx(Q_SIGNED); }
    @Bean public Queue qDeclined()  { return withDlx(Q_DECLINED); }
    @Bean public Queue qReviewed()  { return withDlx(Q_REVIEWED); }

    private Queue withDlx(String name) {
        return QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", Q_DLQ)
                .build();
    }

    // ── Outbound bindings ─────────────────────────────────────────────────

    @Bean public Binding bindSubmitted() { return bind(qSubmitted(), RK_SUBMITTED); }
    @Bean public Binding bindSigned()    { return bind(qSigned(),    RK_SIGNED);    }
    @Bean public Binding bindDeclined()  { return bind(qDeclined(),  RK_DECLINED);  }
    @Bean public Binding bindReviewed()  { return bind(qReviewed(),  RK_REVIEWED);  }

    private Binding bind(Queue q, String rk) {
        return BindingBuilder.bind(q).to(eformsExchange()).with(rk);
    }

    // ── Inbound: workflow.completed consumer ─────────────────────────────
    // FIX: Previously MISSING. Without this queue + binding ecm-eforms never
    // learned about workflow approval/rejection, so:
    //  - FormSubmission.status was never updated to APPROVED/REJECTED
    //  - No document was ever created in ecm_core.documents
    //  - Approved forms were invisible in the document list

    @Bean
    public Queue qWorkflowCompleted() {
        return QueueBuilder.durable(Q_WORKFLOW_COMPLETED)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", Q_DLQ)
                .build();
    }

    @Bean
    public Binding bindWorkflowCompleted(Queue qWorkflowCompleted) {
        // Passive reference to ecm.workflow exchange — owned by ecm-workflow.
        TopicExchange workflowExchangeRef =
                ExchangeBuilder.topicExchange(WORKFLOW_EXCHANGE).durable(true).build();
        return BindingBuilder.bind(qWorkflowCompleted).to(workflowExchangeRef)
                .with(RK_WORKFLOW_COMPLETED);
    }

    // ── Jackson message converter ─────────────────────────────────────────

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf) {
        RabbitTemplate tpl = new RabbitTemplate(cf);
        tpl.setMessageConverter(messageConverter());
        return tpl;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory cf) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(cf);
        factory.setMessageConverter(messageConverter());
        return factory;
    }
}
