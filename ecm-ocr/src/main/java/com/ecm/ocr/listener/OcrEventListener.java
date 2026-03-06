package com.ecm.ocr.listener;

import com.ecm.ocr.config.OcrRabbitConfig;
import com.ecm.ocr.event.OcrRequestMessage;
import com.ecm.ocr.pipeline.OcrPipelineService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Consumes OCR request events from ecm.ocr.requests.
 *
 * Manual acknowledgement: ACK on success, NAK on failure.
 * NAK (requeue=false) routes the message to the configured DLQ
 * (ecm.ocr.requests.dlq) after max-retries is exceeded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcrEventListener {

    private final OcrPipelineService pipeline;

    @RabbitListener(
            queues      = OcrRabbitConfig.OCR_REQUESTS_Q,
            containerFactory = "rabbitListenerContainerFactory",
            ackMode     = "MANUAL"
    )
    public void onOcrRequest(@Payload OcrRequestMessage message,
                             Channel channel,
                             Message amqpMessage) throws IOException {

        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        log.info("OCR event received: documentId={}, tag={}",
                message.documentId(), deliveryTag);

        try {
            pipeline.process(message);
            channel.basicAck(deliveryTag, false);
            log.debug("ACK sent for deliveryTag={}", deliveryTag);

        } catch (Exception e) {
            log.error("OCR processing failed, NAKing: documentId={}, tag={}: {}",
                    message.documentId(), deliveryTag, e.getMessage());
            // requeue=false → message goes to DLQ
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
