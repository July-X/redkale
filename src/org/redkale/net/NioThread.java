/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net;

import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.redkale.util.*;

/**
 * 协议处理的IO线程类
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 *
 * @since 2.1.0
 */
public class NioThread extends WorkThread {

    final Selector selector;

    private final ObjectPool<ByteBuffer> bufferPool;

    private final ObjectPool<Response> responsePool;

    private final ConcurrentLinkedQueue<Consumer<Selector>> registers = new ConcurrentLinkedQueue<>();

    private boolean closed;

    public NioThread(String name, ExecutorService workExecutor, Selector selector,
        ObjectPool<ByteBuffer> bufferPool, ObjectPool<Response> responsePool) {
        super(name, workExecutor, null);
        this.selector = selector;
        this.bufferPool = bufferPool;
        this.responsePool = responsePool;
        this.setDaemon(true);
    }

    public void register(Consumer<Selector> consumer) {
        registers.offer(consumer);
        selector.wakeup();
    }

    public ObjectPool<ByteBuffer> getBufferPool() {
        return bufferPool;
    }

    public ObjectPool<Response> getResponsePool() {
        return responsePool;
    }

    @Override
    public void run() {
        this.localThread = Thread.currentThread();
        while (!this.closed) {
            try {
                Consumer<Selector> register;
                while ((register = registers.poll()) != null) {
                    register.accept(selector);
                }
                int count = selector.select();
                if (count == 0) continue;
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) continue;
                    NioTcpAsyncConnection conn = (NioTcpAsyncConnection) key.attachment();
                    if (key.isWritable()) {
                        //key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                        conn.doWrite();
                    } else if (key.isReadable()) {
                        conn.doRead();
                    } else if (key.isConnectable()) {
                        conn.doConnect();
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    public void close() {
        this.closed = true;
        this.interrupt();
    }
}