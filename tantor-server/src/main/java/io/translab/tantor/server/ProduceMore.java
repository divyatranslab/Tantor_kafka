package io.translab.tantor.server;

import org.apache.kafka.clients.producer.*;
import java.util.*;

public class ProduceMore {
    public static void main(String[] args) throws Exception {
        String bootstrap = "192.168.3.149:9088";
        String topic = "yawar-topic-1";
        
        Properties prodProps = new Properties();
        prodProps.put("bootstrap.servers", bootstrap);
        prodProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        prodProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        
        try (Producer<String, String> producer = new KafkaProducer<>(prodProps)) {
            for (int i = 0; i < 246; i++) {
                producer.send(new ProducerRecord<>(topic, "key", "msg" + i));
            }
            System.out.println("Produced 246 more messages. Generating lag...");
        }
    }
}
