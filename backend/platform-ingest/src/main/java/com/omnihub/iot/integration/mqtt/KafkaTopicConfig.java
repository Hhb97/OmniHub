package com.omnihub.iot.integration.mqtt;

import com.omnihub.iot.config.IotProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Creates the uplink topic on startup when it does not exist (kafka mode, iot.ingest.kafka.create-topic). */
@Configuration
@ConditionalOnProperty(prefix = "iot.ingest", name = "mode", havingValue = "kafka")
public class KafkaTopicConfig {

    @Bean
    @ConditionalOnProperty(prefix = "iot.ingest.kafka", name = "create-topic", havingValue = "true", matchIfMissing = true)
    public NewTopic uplinkTopic(IotProperties props) {
        IotProperties.Kafka k = props.getIngest().getKafka();
        return TopicBuilder.name(k.getTopic()).partitions(k.getPartitions()).replicas(k.getReplicas()).build();
    }
}
