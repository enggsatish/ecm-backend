package com.ecm.batch.messaging;

import com.ecm.batch.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BatchItemProducer {

    private final RabbitTemplate rabbitTemplate;

    public void sendProcessMessage(BatchItemMessage message) {
        log.debug("Sending batch item process message: itemId={}, batchId={}",
                message.itemId(), message.batchId());
        rabbitTemplate.convertAndSend(
                RabbitConfig.EXCHANGE,
                RabbitConfig.ITEM_PROCESS_ROUTING_KEY,
                message
        );
    }

    public void sendResultMessage(BatchItemMessage message) {
        log.debug("Sending batch item result message: itemId={}", message.itemId());
        rabbitTemplate.convertAndSend(
                RabbitConfig.EXCHANGE,
                RabbitConfig.ITEM_RESULT_ROUTING_KEY,
                message
        );
    }
}
