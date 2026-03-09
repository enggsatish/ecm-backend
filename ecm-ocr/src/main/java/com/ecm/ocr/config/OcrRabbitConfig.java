package com.ecm.ocr.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * RabbitMQ configuration for ecm-ocr.
 *
 * ── Three bugs fixed in this revision ───────────────────────────────────────
 *
 * BUG 1 — __TypeId__ class mismatch (primary cause of PENDING_OCR never updating)
 *   ecm-document publishes OcrRequestEvent with header:
 *     __TypeId__ = com.ecm.document.event.OcrRequestEvent
 *   The default Jackson2JsonMessageConverter on the consumer side reads this header
 *   and calls Class.forName("com.ecm.document.event.OcrRequestEvent").
 *   That class does NOT exist in ecm-ocr's classpath → ClassNotFoundException →
 *   deserialization fails → listener throws before any user code runs →
 *   Spring AMQP NAKs → message goes to DLQ silently → document stuck at PENDING_OCR.
 *
 *   FIX: Call converter.setAlwaysConvertToInferredType(true).
 *   This tells the converter to IGNORE __TypeId__ and instead infer the target
 *   type from the @Payload method parameter (OcrRequestMessage). Jackson then
 *   deserialises the JSON body directly into OcrRequestMessage — no cross-service
 *   class resolution needed. The two records (OcrRequestEvent / OcrRequestMessage)
 *   have identical JSON shapes, so no data loss occurs.
 *
 * BUG 2 — Missing rabbitListenerContainerFactory with MANUAL ack
 *   OcrEventListener declares containerFactory="rabbitListenerContainerFactory"
 *   and ackMode="MANUAL", but OcrRabbitConfig never defined this bean.
 *   Spring Boot's auto-configured factory defaults to AUTO ack and uses
 *   SimpleMessageConverter (raw bytes). The channel.basicAck() /
 *   channel.basicNack() calls inside the listener were effectively no-ops.
 *
 *   FIX: Declare an explicit SimpleRabbitListenerContainerFactory bean named
 *   "rabbitListenerContainerFactory" with MANUAL acknowledge mode and the
 *   fixed Jackson converter.
 *
 * BUG 3 — ocrMessageConverter not wired into RabbitTemplate
 *   OcrPipelineService injected RabbitTemplate by type and got Spring Boot's
 *   auto-configured one (SimpleMessageConverter). Step 7 — publishing
 *   OcrCompletedEvent to ecm.ocr.completed — serialised as raw Java bytes,
 *   not JSON. Any downstream subscriber would fail to deserialise it.
 *
 *   FIX: Declare an explicit @Primary RabbitTemplate bean wired with the same
 *   Jackson converter. OcrPipelineService's @RequiredArgsConstructor injection
 *   will now receive this bean.
 *
 * ── Queue topology (unchanged) ───────────────────────────────────────────────
 *   ecm.ocr.requests      — durable, DLX → ecm.ocr.dlq (declared by ecm-document too;
 *                           re-declares are no-ops because args are identical)
 *   ecm.ocr.requests.dlq  — dead-letter for failed OCR attempts
 *   ecm.ocr.completed     — fanout exchange; downstream subscribers bind their own queues
 */
@Configuration
public class OcrRabbitConfig {

    public static final String OCR_REQUESTS_Q   = "ecm.ocr.requests";
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

    // ── Main OCR requests queue ──────────────────────────────────────
    // Re-declared here with identical DLX args as ecm-document — RabbitMQ
    // treats this as a no-op. Args MUST match; any difference causes
    // AMQP PRECONDITION_FAILED on startup.

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

    // ── Message converter (FIX for Bug 1) ───────────────────────────
    //
    // setAlwaysConvertToInferredType(true) instructs the converter to ignore
    // the __TypeId__ AMQP header sent by the publisher and instead infer the
    // target type from the @Payload method parameter type at the listener.
    //
    // Without this, the converter tries Class.forName(__TypeId__) which resolves
    // to "com.ecm.document.event.OcrRequestEvent" — a class that does not exist
    // in this module's classpath — causing ClassNotFoundException on every message.

    @Bean
    @Primary
    public Jackson2JsonMessageConverter ocrMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    // ── RabbitTemplate (FIX for Bug 3) ──────────────────────────────
    //
    // Explicit bean ensures OcrPipelineService.rabbit uses the Jackson converter
    // when publishing OcrCompletedEvent to ecm.ocr.completed.
    // Without this, Spring Boot's auto-configured template uses SimpleMessageConverter
    // and the completion event is serialised as raw Java bytes, not JSON.

    @Bean
    @Primary
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter ocrMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(ocrMessageConverter);
        return template;
    }

    // ── Listener container factory (FIX for Bug 2) ──────────────────
    //
    // OcrEventListener declares containerFactory="rabbitListenerContainerFactory"
    // and ackMode="MANUAL". This bean was missing from the original config,
    // so Spring Boot's default factory (AUTO ack, SimpleMessageConverter) was used.
    //
    // MANUAL ack is required so the listener can:
    //   - basicAck on success  → message removed from queue
    //   - basicNack(requeue=false) on failure → message routes to DLQ
    //
    // prefetchCount=1 ensures ecm-ocr processes one document at a time,
    // preventing memory exhaustion when multiple large files are queued simultaneously.

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter ocrMessageConverter) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(ocrMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        return factory;
    }
}