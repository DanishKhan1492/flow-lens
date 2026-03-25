package com.dnlabz.flowlens.starter.model;

/**
 * High-level category of the traced entry point.
 */
public enum EntryPointType {
    /** HTTP request handled by a @RestController / @Controller method. */
    API,
    /** Message consumer: @KafkaListener, @RabbitListener, @SqsListener, etc. */
    CONSUMER,
    /** Scheduled task: @Scheduled method. */
    SCHEDULER
}
