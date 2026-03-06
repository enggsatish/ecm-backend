package com.ecm.ocr.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares OCR queues and the completion exchange.
 *
 * ecm.documents exchange (topic, durable) and ecm.ocr.requests queue are
 * already declared by ecm-document. This config only declares what ecm-ocr owns:
 *   - ecm.ocr.requests.dlq  — dead-letter for failed OCR attempts
 *   - ecm.ocr.completed     — fanout exchange for downstream consumers
 *
 * Note: RabbitMQ is idempotent on declare — re-declaring existing queues/exchanges
 * with the same arguments is safe and will not cause errors.
 */
@Configuration
public class OcrRabbitConfig {

    public static final String OCR_REQUESTS_Q  = "ecm.ocr.requests";
    public static final String OCR_REQUESTS_DLQ = "ecm.ocr.requests.dlq";
    public static final String OCR_DLQ_EXCHANGE = "ecm.ocr.dlq";
    public static final String OCR_COMPLETED_EX = "ecm.ocr.completed";

    // ── DLQ infrastructure ───────────────────────────────────────────

    @Bean
    public DirectExchange ocrDlqExchange() {
        return ExchangeBuilder.directExchange(OCR_DLQ_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue ocrRequestsDlq() {
        return QueueBuilder.durable(OCR_REQUESTS_DLQ).build();
    }

    @Bean
    public Binding ocrDlqBinding(Queue ocrRequestsDlq, DirectExchange ocrDlqExchange) {
        return BindingBuilder.bind(ocrRequestsDlq).to(ocrDlqExchange).with(OCR_REQUESTS_DLQ);
    }

    // ── Main OCR requests queue (declared by ecm-document; we re-declare here
    //    with DLQ config — idempotent only if args match exactly) ────────

    @Bean
    public Queue ocrRequestsQueue() {
        return QueueBuilder.durable(OCR_REQUESTS_Q)
                .deadLetterExchange(OCR_DLQ_EXCHANGE)
                .deadLetterRoutingKey(OCR_REQUESTS_DLQ)
                .build();
    }

    // ── Completion exchange ──────────────────────────────────────────

    @Bean
    public FanoutExchange ocrCompletedExchange() {
        return ExchangeBuilder.fanoutExchange(OCR_COMPLETED_EX).durable(true).build();
    }

    // ── Message converter ────────────────────────────────────────────

    @Bean
    public Jackson2JsonMessageConverter ocrMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
