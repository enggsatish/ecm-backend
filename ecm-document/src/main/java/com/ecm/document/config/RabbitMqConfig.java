package com.ecm.document.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the ECM document exchange and the OCR request queue.
 * <p>
 * Exchange: {@code ecm.documents}  (topic, durable)
 * Queue:    {@code ecm.ocr.requests}  (durable, with DLX so ecm-ocr's declaration is idempotent)
 * Binding:  routing key {@code document.ocr.request}
 * <p>
 * IMPORTANT — DLX args MUST match ecm-ocr/OcrRabbitConfig exactly.
 * RabbitMQ will throw PRECONDITION_FAILED if the same queue is declared
 * twice with different arguments (even one module missing the DLX args).
 * Both modules declare identical args; RabbitMQ treats re-declares as no-ops.
 */
@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE             = "ecm.documents";
    public static final String OCR_QUEUE            = "ecm.ocr.requests";
    public static final String OCR_ROUTING_KEY      = "document.ocr.request";

    // Must match OcrRabbitConfig constants in ecm-ocr
    public static final String OCR_DLQ_EXCHANGE     = "ecm.ocr.dlq";
    public static final String OCR_DLQ_ROUTING_KEY  = "ecm.ocr.requests.dlq";
    public static final String OCR_DLQ_QUEUE        = "ecm.ocr.requests.dlq";
    public static final String WORKFLOW_TRIGGER_ROUTING_KEY = "document.workflow.trigger";

    @Bean
    public TopicExchange documentExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    /**
     * OCR request queue WITH dead-letter exchange args.
     * Args must be identical to ecm-ocr's declaration — any mismatch causes
     * AMQP PRECONDITION_FAILED on startup.
     */
    @Bean
    public Queue ocrRequestQueue() {
        return QueueBuilder.durable(OCR_QUEUE)
                .deadLetterExchange(OCR_DLQ_EXCHANGE)
                .deadLetterRoutingKey(OCR_DLQ_ROUTING_KEY)
                .build();
    }

    /** DLQ exchange — also owned jointly; idempotent on re-declare. */
    @Bean
    public DirectExchange ocrDlqExchange() {
        return ExchangeBuilder.directExchange(OCR_DLQ_EXCHANGE).durable(true).build();
    }

    /** Dead-letter queue for failed OCR messages. */
    @Bean
    public Queue ocrRequestsDlq() {
        return QueueBuilder.durable(OCR_DLQ_QUEUE).build();
    }

    @Bean
    public Binding ocrDlqBinding(Queue ocrRequestsDlq, DirectExchange ocrDlqExchange) {
        return BindingBuilder.bind(ocrRequestsDlq).to(ocrDlqExchange).with(OCR_DLQ_QUEUE);
    }

    @Bean
    public Binding ocrBinding(Queue ocrRequestQueue, TopicExchange documentExchange) {
        return BindingBuilder
                .bind(ocrRequestQueue)
                .to(documentExchange)
                .with(OCR_ROUTING_KEY);
    }

    /** Use JSON so the OCR consumer (any language) can deserialise events easily. */
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
/** Commented below code due to following reason: A publisher only needs the exchange to route a message — it has no
 * business declaring a queue it doesn't own.
 * The WORKFLOW_TRIGGER_QUEUE constant was also removed (the queue name is meaningless to the publisher).
 * The routing key constant stays because DocumentServiceImpl.publishWorkflowTriggerEvent() still references it.*/
//    @Bean
//    public Queue workflowTriggerQueue() {
//        return QueueBuilder.durable(WORKFLOW_TRIGGER_QUEUE).build();
//    }
//
//    @Bean
//    public Binding workflowTriggerBinding(Queue workflowTriggerQueue,
//                                          TopicExchange documentExchange) {
//        return BindingBuilder.bind(workflowTriggerQueue)
//                .to(documentExchange)
//                .with(WORKFLOW_TRIGGER_ROUTING_KEY);
//    }
}