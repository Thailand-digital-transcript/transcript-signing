package com.wpanther.transcript.signing.integration.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.*;

public class KafkaTestHelper {

    private final String bootstrapServers;
    private final ObjectMapper objectMapper;

    public KafkaTestHelper(String bootstrapServers, ObjectMapper objectMapper) {
        this.bootstrapServers = bootstrapServers;
        this.objectMapper = objectMapper;
    }

    public void sendCommand(String topic, Object command) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            String json = objectMapper.writeValueAsString(command);
            producer.send(new ProducerRecord<>(topic, json)).get();
        }
    }

    public Optional<ConsumerRecord<String, String>> pollOne(String topic, Duration timeout) {
        return pollFor(topic, timeout, null);
    }

    /**
     * Reads from the beginning of {@code topic} and returns the first record whose value contains
     * {@code mustContain}, or any record if {@code mustContain} is null. Integration test topics are
     * shared (and never purged) across test classes, so a fresh consumer group reading from earliest
     * sees stale records from prior tests. Scanning for a uniquely-identifying token (e.g. the test's
     * own sagaId or documentId) isolates each test's expected message from that backlog.
     */
    public Optional<ConsumerRecord<String, String>> pollFor(String topic, Duration timeout,
                                                            String mustContain) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            do {
                var records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (mustContain == null || record.value().contains(mustContain)) {
                        return Optional.of(record);
                    }
                }
            } while (System.currentTimeMillis() < deadline);
            return Optional.empty();
        }
    }
}
