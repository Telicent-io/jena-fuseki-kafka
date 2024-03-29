/*
 *  Copyright (c) Telicent Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.jena.fuseki.kafka;

import java.time.Duration;
import java.util.Objects;

import org.apache.jena.atlas.lib.Timer;
import org.apache.jena.atlas.logging.FmtLog;
import org.apache.jena.atlas.logging.Log;
import org.apache.jena.kafka.FusekiKafka;
import org.apache.jena.kafka.RequestFK;
import org.apache.jena.kafka.common.DataState;
import org.apache.jena.sparql.core.Transactional;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;

/**
 * The engine for the Kafka-Fuseki connector.
 * <p>
 * This reads the Kafka topic and sends batches to a {@link FKProcessor}.
 */
public class FKBatchProcessor {

    private static Logger LOG = FusekiKafka.LOG;

    /**
     * A batch processor that applies a FKProcessor fkProcessor.
     * The {@link FKProcessor} is responsible for transactions.
     */
    public static FKBatchProcessor createBatchProcessor(FKProcessor fkProcessor) {
        return new FKBatchProcessor(fkProcessor);
    }

    private final Transactional transactional;
    private final FKProcessor   fkProcessor;

    /**
     * Batch processor that applies a {@link FKProcessor} to each item in the batch.
     */
    private FKBatchProcessor(FKProcessor fkProcessor) {
        this(null, fkProcessor);
    }

    /**
     * Batch processor.
     * If the {@link Transactional} is provided,
     * it puts a transaction around the loop that applies a
     * {@link FKProcessor} to each item in the batch.
     * If the transactional is null, no transaction is added and the processor is in control.
     */
    public FKBatchProcessor(Transactional transactional, FKProcessor fkProcessor) {
        this.transactional = transactional;
        this.fkProcessor = fkProcessor;
    }

    /**
     * Round the polling loop, updating the record.
     * Return true if some processing happened.
     */
    public boolean receiver(Consumer<String, RequestFK> consumer, DataState dataState, Duration initialPollingDuration) {
        Objects.requireNonNull(consumer);
        Objects.requireNonNull(dataState);

        Duration  pollingDuration = initialPollingDuration;

        if ( pollingDuration == null )
            pollingDuration = FKConst.pollingWaitDuration;
        final long lastOffsetState = dataState.getLastOffset();
        final String topic = dataState.getTopic();
        try {
            boolean rtn = false;
            long commitedState = lastOffsetState;
            int i;
            for ( i = 0 ; i < FKConst.MAX_LOOPS_PER_CYCLE ; i++ ) {
                long newOffset = receiverStep(topic, dataState.getLastOffset(), consumer, pollingDuration);
                if ( newOffset == commitedState ) {
                    // Nothing received.
                    break;
                }
                dataState.setLastOffset(newOffset);
                commitedState = newOffset;
                rtn = true;
                // Switch to shorter polling wait
                pollingDuration = FKConst.pollingWaitDurationMore;
            }
            if ( LOG.isDebugEnabled() )
                FmtLog.debug(LOG, "[%s] Exit receiver loop at i=%d", topic, i);
            return rtn;
        } catch (RuntimeException ex) {
            String x = String.format("[%s] %s", dataState.getTopic(), ex.getMessage());
            Log.error(LOG, x, ex);
            return false;
        }
    }

    private static final boolean VERBOSE = true;

    /** Do one Kafka consumer poll step. */
    private long receiverStep(String topic, long lastOffsetState, Consumer<String, RequestFK> consumer, Duration pollingDuration) {
        Objects.requireNonNull(pollingDuration);
        Objects.requireNonNull(consumer);
        if ( LOG.isDebugEnabled() )
            FmtLog.debug(LOG, "[%s] consumer.poll(%s ms)", topic, pollingDuration.toMillis());
        ConsumerRecords<String, RequestFK> cRecords = consumer.poll(pollingDuration);
        return processBatch(topic, lastOffsetState, cRecords);
    }

    public long processBatch(String topic, long lastOffsetState, ConsumerRecords<String, RequestFK> cRecords) {
        if ( cRecords.isEmpty() )
            // Nothing received - no change.
            return lastOffsetState;

        int count = cRecords.count();
        long payloadSize = payloadSize(cRecords);
        Timer timer = batchStart(topic, lastOffsetState, count, payloadSize);

        long newOffset = batchProcess(topic, cRecords);

        // Check expectation.
        long newOffset2 = lastOffsetState + count;
        if ( newOffset != newOffset2 )
            // Transaction control messages and topic key-based compaction mean this test isn't strictly true.
            // This log message is just an indication of this.
            FmtLog.info(LOG, "[%s] Batch offsets not as predicated: [actual=%d, predicated=%d]", topic, newOffset, newOffset2);

        batchFinish(topic, lastOffsetState, newOffset, timer);

        return newOffset;
    }

    private long payloadSize(ConsumerRecords<String, RequestFK> cRecords) {
        long sizeBytes = 0;
        for ( ConsumerRecord<String, RequestFK> cRec : cRecords ) {
            sizeBytes += cRec.value().getByteCount();
        }
        return sizeBytes;
    }

    private Timer batchStart(String topic, long lastOffsetState, int count, long numBytes) {
        FmtLog.info(LOG, "[%s] Batch: Start offset = %d ; Count = %d : Payload = %,d bytes", topic, lastOffsetState, count, numBytes);
        if ( ! VERBOSE )
            return null;
        Timer timer = new Timer();
        timer.startTimer();
        fkProcessor.startBatch(count, lastOffsetState);
        return timer;
    }

    private long batchProcess(String topic, ConsumerRecords<String, RequestFK> cRecords) {
        if ( transactional == null ) {
            // No transactional set. Assume the fkProcessor.process knows what it is doing.
            return execBatch(cRecords);
        }
        return transactional.calculate(()->execBatch(cRecords));
    }

    private void batchFinish(String topic, long lastOffsetState, long newOffsetState, Timer timer) {
        int numProcessed = Math.toIntExact(newOffsetState - lastOffsetState);

        fkProcessor.finishBatch(numProcessed, newOffsetState, lastOffsetState);
        if ( timer == null )
            FmtLog.info(LOG, "[%s] Batch: Finished [%d, %d]", topic, lastOffsetState, newOffsetState);
        else {
            long z = timer.endTimer();
            FmtLog.info(LOG, "[%s] Batch: Finished [%d, %d] in %s seconds", topic, lastOffsetState, newOffsetState, Timer.timeStr(z));
        }
    }

    /** Execute a batch - return the new last seen offset. */
    private long execBatch(ConsumerRecords<String, RequestFK> cRecords) {
        long lastOffset = -1;
        for ( ConsumerRecord<String, RequestFK> cRec : cRecords ) {
            RequestFK requestFK = cRec.value();
            if ( LOG.isDebugEnabled() )
                FmtLog.debug(LOG, "[%s] Record Offset %s", requestFK.getTopic(), cRec.offset());
            try {
                fkProcessor.process(requestFK);
                lastOffset = cRec.offset();
            } catch(Throwable ex) {
                // Something unexpected went wrong.
                // Polling is asynchronous to the server.
                // When shutting down, various things can go wrong.
                // Log and ignore!
                FmtLog.warn(LOG, ex, "Exception in processing: %s", ex.getMessage());
            }
        }
        return lastOffset;
    }
}
