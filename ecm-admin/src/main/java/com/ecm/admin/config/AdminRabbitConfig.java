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
    public static final String RK_CASE_OTP          = "case.otp.requested";
    public static final String RK_CASE_INVITE       = "case.participant.added";
    public static final String Q_USER_DEACTIVATED   = "ecm.admin.user.deactivated";
    public static final String Q_CATEGORY_UPDATED   = "ecm.admin.category.updated";

    // Workflow exchange — ecm-admin listens for workflow completion events
    public static final String WORKFLOW_EXCHANGE     = "ecm.workflow";
    public static final String Q_WORKFLOW_COMPLETED  = "ecm.admin.workflow.completed";

    // Document exchange — ecm-admin listens for document.classified events (case auto-attach)
    public static final String DOCUMENT_EXCHANGE         = "ecm.documents";
    public static final String Q_DOCUMENT_CLASSIFIED     = "ecm.admin.document.classified";
    public static final String RK_DOCUMENT_CLASSIFIED    = "document.classified";

    @Bean
    TopicExchange adminExchange() {
        return ExchangeBuilder.topicExchange(ADMIN_EXCHANGE).durable(true).build();
    }

    @Bean
    TopicExchange workflowExchange() {
        return ExchangeBuilder.topicExchange(WORKFLOW_EXCHANGE).durable(true).build();
    }

    @Bean Queue userDeactivatedQueue() { return QueueBuilder.durable(Q_USER_DEACTIVATED).build(); }
    @Bean Queue categoryUpdatedQueue() { return QueueBuilder.durable(Q_CATEGORY_UPDATED).build(); }
    @Bean Queue workflowCompletedQueue() { return QueueBuilder.durable(Q_WORKFLOW_COMPLETED).build(); }
    @Bean Queue documentClassifiedQueue() { return QueueBuilder.durable(Q_DOCUMENT_CLASSIFIED).build(); }

    @Bean
    Binding userDeactivatedBinding(TopicExchange adminExchange) {
        return BindingBuilder.bind(userDeactivatedQueue()).to(adminExchange).with(RK_USER_DEACTIVATED);
    }

    @Bean
    Binding categoryUpdatedBinding(TopicExchange adminExchange) {
        return BindingBuilder.bind(categoryUpdatedQueue()).to(adminExchange).with(RK_CATEGORY_UPDATED);
    }

    @Bean
    Binding workflowCompletedBinding(Queue workflowCompletedQueue, TopicExchange workflowExchange) {
        return BindingBuilder.bind(workflowCompletedQueue).to(workflowExchange).with("workflow.completed");
    }

    @Bean
    TopicExchange documentExchange() {
        return ExchangeBuilder.topicExchange(DOCUMENT_EXCHANGE).durable(true).build();
    }

    @Bean
    Binding documentClassifiedBinding(Queue documentClassifiedQueue, TopicExchange documentExchange) {
        return BindingBuilder.bind(documentClassifiedQueue).to(documentExchange).with(RK_DOCUMENT_CLASSIFIED);
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
