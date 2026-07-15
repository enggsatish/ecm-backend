package com.ecm.batch.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE                  = "ecm.batch";
    public static final String ITEM_PROCESS_QUEUE        = "ecm.batch.item.process";
    public static final String ITEM_RESULT_QUEUE         = "ecm.batch.item.result";
    public static final String REVIEW_NOTIFY_QUEUE       = "ecm.batch.review.notify";

    public static final String ITEM_PROCESS_ROUTING_KEY  = "batch.item.process";
    public static final String ITEM_RESULT_ROUTING_KEY   = "batch.item.result";
    public static final String REVIEW_NOTIFY_ROUTING_KEY = "batch.review.notify";

    @Bean
    public TopicExchange batchExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue itemProcessQueue() {
        return QueueBuilder.durable(ITEM_PROCESS_QUEUE).build();
    }

    @Bean
    public Queue itemResultQueue() {
        return QueueBuilder.durable(ITEM_RESULT_QUEUE).build();
    }

    @Bean
    public Queue reviewNotifyQueue() {
        return QueueBuilder.durable(REVIEW_NOTIFY_QUEUE).build();
    }

    @Bean
    public Binding itemProcessBinding(Queue itemProcessQueue, TopicExchange batchExchange) {
        return BindingBuilder.bind(itemProcessQueue).to(batchExchange).with(ITEM_PROCESS_ROUTING_KEY);
    }

    @Bean
    public Binding itemResultBinding(Queue itemResultQueue, TopicExchange batchExchange) {
        return BindingBuilder.bind(itemResultQueue).to(batchExchange).with(ITEM_RESULT_ROUTING_KEY);
    }

    @Bean
    public Binding reviewNotifyBinding(Queue reviewNotifyQueue, TopicExchange batchExchange) {
        return BindingBuilder.bind(reviewNotifyQueue).to(batchExchange).with(REVIEW_NOTIFY_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(cf);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
