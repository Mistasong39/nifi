/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.kafka.pubsub;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class StubPublishKafka extends PublishKafka {

    private volatile Producer<byte[], byte[]> producer;

    private volatile boolean failed;

    public Producer<byte[], byte[]> getProducer() {
        return producer;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected KafkaPublisher buildKafkaResource(ProcessContext context, ProcessSession session)
            throws ProcessException {
        Properties kafkaProperties = this.buildKafkaProperties(context);
        kafkaProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        kafkaProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        KafkaPublisher publisher;
        try {
            Field f = PublishKafka.class.getDeclaredField("brokers");
            f.setAccessible(true);
            f.set(this, context.getProperty(BOOTSTRAP_SERVERS).evaluateAttributeExpressions().getValue());
            publisher = (KafkaPublisher) TestUtils.getUnsafe().allocateInstance(KafkaPublisher.class);
            producer = mock(Producer.class);
            this.instrumentProducer(producer, false);
            Field kf = KafkaPublisher.class.getDeclaredField("kafkaProducer");
            kf.setAccessible(true);
            kf.set(publisher, producer);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return publisher;
    }

    @SuppressWarnings("unchecked")
    private void instrumentProducer(Producer<byte[], byte[]> producer, boolean failRandomly) {
        when(producer.send(Mockito.any(ProducerRecord.class))).then(new Answer<Future<RecordMetadata>>() {
            @SuppressWarnings("rawtypes")
            @Override
            public Future<RecordMetadata> answer(InvocationOnMock invocation) throws Throwable {
                ProducerRecord<byte[], byte[]> record = (ProducerRecord<byte[], byte[]>) invocation.getArguments()[0];
                String value = new String(record.value(), StandardCharsets.UTF_8);
                if ("fail".equals(value) && !StubPublishKafka.this.failed) {
                    StubPublishKafka.this.failed = true;
                    throw new RuntimeException("intentional");
                }
                Future future = mock(Future.class);
                if ("futurefail".equals(value) && !StubPublishKafka.this.failed) {
                    StubPublishKafka.this.failed = true;
                    when(future.get(Mockito.anyLong(), Mockito.any())).thenThrow(ExecutionException.class);
                }
                return future;
            }
        });
    }
}
