/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.mq;

import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
public abstract class MessageStreams extends Thread {

    protected final String topic;

    protected final Function<MessageRecord, MessageRecord> processor;

    protected final Logger logger = Logger.getLogger(this.getClass().getSimpleName());

    protected volatile boolean closed;

    protected MessageStreams(String topic, Function<MessageRecord, MessageRecord> processor) {
        Objects.requireNonNull(topic);
        Objects.requireNonNull(processor);
        this.topic = topic;
        this.processor = processor;
    }

    public Function<MessageRecord, MessageRecord> getProcessor() {
        return processor;
    }

    public String getTopic() {
        return topic;
    }

    public boolean isClosed() {
        return closed;
    }

    public abstract void close();
}