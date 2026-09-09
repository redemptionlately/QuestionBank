package com.allen.questionbank.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 消费失败的兜底路径：重试 2 次（间隔 200ms）仍失败 → 原消息投递到 <topic>.DLT 死信主题，
 * 不阻塞分区、不静默丢失。死信消息带 kafka_dpl-* 头（原主题/分区/异常），人工排查后可重放。
 * 与 Outbox 的关系：Outbox 保证"不丢"（投递侧），幂等保证"不重"（消费侧），
 * DLT 处理的是第三种情况——消息合法但业务处理持续失败。
 */
@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        // 并发消费 = 3。两个前提缺一不可：
        // 1. 分区数 >= 并发度，否则多出的 consumer 空转（start-evidence-env.sh 幂等建 qb-events 3 分区）；
        // 2. per-key 有序性——发送侧（OutboxRelay）按 eventKey 作 key，同 key 恒定落同一分区，
        //    并发消费不会打乱单事件键的顺序；跨 key 的重复消费由 eventKey 唯一键幂等兜底。
        factory.setConcurrency(3);
        // DLT 主题命名：<原主题>.DLT；key 保持原样，便于按事件键追踪
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(200L, 2L)));
        return factory;
    }
}
