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
 * Exchange:  ecm.eforms   (topic)
 * Queues published by ecm-eforms (consumed by ecm-workflow / ecm-notification):
 *   ecm.form.submitted  ← routing: form.submitted  → ecm-workflow creates WorkflowInstance
 *   ecm.form.signed     ← routing: form.signed      → ecm-workflow moves task to SIGNED
 *   ecm.form.declined   ← routing: form.sign.declined
 *   ecm.form.reviewed   ← routing: form.reviewed    → ecm-notification emails submitter
 *
 * Dead-letter queue:
 *   ecm.eforms.dlq — catches failed messages from all four queues
 */
@Configuration
public class EFormsRabbitConfig {

    // ── Exchange ──────────────────────────────────────────────────────

    public static final String EXCHANGE = "ecm.eforms";

    // ── Routing keys ──────────────────────────────────────────────────

    public static final String RK_SUBMITTED = "form.submitted";
    public static final String RK_SIGNED    = "form.signed";
    public static final String RK_DECLINED  = "form.sign.declined";
    public static final String RK_REVIEWED  = "form.reviewed";

    // ── Queue names ───────────────────────────────────────────────────

    public static final String Q_SUBMITTED = "ecm.form.submitted";
    public static final String Q_SIGNED    = "ecm.form.signed";
    public static final String Q_DECLINED  = "ecm.form.declined";
    public static final String Q_REVIEWED  = "ecm.form.reviewed";
    public static final String Q_DLQ       = "ecm.eforms.dlq";

    // ── Exchange bean ─────────────────────────────────────────────────

    @Bean
    public TopicExchange eformsExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue dlq() {
        return QueueBuilder.durable(Q_DLQ).build();
    }

    // ── Queues with DLX ──────────────────────────────────────────────

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

    // ── Bindings ──────────────────────────────────────────────────────

    @Bean public Binding bindSubmitted() { return bind(qSubmitted(), RK_SUBMITTED); }
    @Bean public Binding bindSigned()    { return bind(qSigned(),    RK_SIGNED);    }
    @Bean public Binding bindDeclined()  { return bind(qDeclined(),  RK_DECLINED);  }
    @Bean public Binding bindReviewed()  { return bind(qReviewed(),  RK_REVIEWED);  }

    private Binding bind(Queue q, String rk) {
        return BindingBuilder.bind(q).to(eformsExchange()).with(rk);
    }

    // ── Jackson message converter ─────────────────────────────────────

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
