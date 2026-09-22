package com.chat99.server.messagearchive;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
@EnableKafka
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class MessageArchiveKafkaConfig {

    @Bean
    @Primary
    public ProducerFactory<String, String> messageArchiveProducerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties(null));
    }

    @Bean
    @Primary
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean(name = "archiveKafkaListenerContainerFactory")
    @ConditionalOnMessageArchiveWorker
    @ConditionalOnProperty(name = "chat99.message-archive.consumer.archive-enabled", havingValue = "true", matchIfMissing = true)
    public ConcurrentKafkaListenerContainerFactory<String, String> archiveKafkaListenerContainerFactory(
        KafkaProperties kafkaProperties,
        MessageArchiveProperties archiveProps) {
        return buildBatchFactory(kafkaProperties, archiveProps, archiveProps.consumer().archiveConcurrency());
    }

    @Bean(name = "pushKafkaListenerContainerFactory")
    @ConditionalOnProperty(
        prefix = "chat99.message-archive.consumer",
        name = "push-enabled",
        havingValue = "true",
        matchIfMissing = false)
    public ConcurrentKafkaListenerContainerFactory<String, String> pushKafkaListenerContainerFactory(
        KafkaProperties kafkaProperties,
        MessageArchiveProperties archiveProps) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setBatchListener(true);
        factory.setConcurrency(archiveProps.consumer().pushConcurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }

    private static ConcurrentKafkaListenerContainerFactory<String, String> buildBatchFactory(
        KafkaProperties kafkaProperties,
        MessageArchiveProperties archiveProps,
        int concurrency) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, archiveProps.consumer().batchSize());
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setBatchListener(true);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
