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
 * Queue:    {@code ecm.ocr.requests}
 * Binding:  routing key {@code document.ocr.request}
 * <p>
 * The future ecm-ocr service will consume from {@code ecm.ocr.requests}.
 */
@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE         = "ecm.documents";
    public static final String OCR_QUEUE        = "ecm.ocr.requests";
    public static final String OCR_ROUTING_KEY  = "document.ocr.request";

    @Bean
    public TopicExchange documentExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue ocrRequestQueue() {
        return QueueBuilder.durable(OCR_QUEUE).build();
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
}