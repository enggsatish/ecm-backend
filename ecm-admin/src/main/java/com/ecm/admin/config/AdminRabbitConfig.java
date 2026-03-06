package com.ecm.admin.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminRabbitConfig {

    public static final String ADMIN_EXCHANGE       = "ecm.admin";
    public static final String RK_USER_DEACTIVATED  = "user.deactivated";
    public static final String RK_CATEGORY_UPDATED  = "category.updated";
    public static final String Q_USER_DEACTIVATED   = "ecm.admin.user.deactivated";
    public static final String Q_CATEGORY_UPDATED   = "ecm.admin.category.updated";

    @Bean
    TopicExchange adminExchange() {
        return ExchangeBuilder.topicExchange(ADMIN_EXCHANGE).durable(true).build();
    }

    @Bean Queue userDeactivatedQueue() { return QueueBuilder.durable(Q_USER_DEACTIVATED).build(); }
    @Bean Queue categoryUpdatedQueue() { return QueueBuilder.durable(Q_CATEGORY_UPDATED).build(); }

    @Bean
    Binding userDeactivatedBinding(TopicExchange adminExchange) {
        return BindingBuilder.bind(userDeactivatedQueue()).to(adminExchange).with(RK_USER_DEACTIVATED);
    }

    @Bean
    Binding categoryUpdatedBinding(TopicExchange adminExchange) {
        return BindingBuilder.bind(categoryUpdatedQueue()).to(adminExchange).with(RK_CATEGORY_UPDATED);
    }

    @Bean
    Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                   Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
