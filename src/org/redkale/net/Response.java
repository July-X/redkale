/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.function.*;
import java.util.logging.Level;
import org.redkale.util.ObjectPool;

/**
 * 协议响应对象
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 * @param <C> Context的子类型
 * @param <R> Request的子类型
 */
@SuppressWarnings("unchecked")
public abstract class Response<C extends Context, R extends Request<C>> {

    protected final C context;

    protected final ObjectPool<Response> responsePool;

    protected final R request;

    protected AsyncConnection channel;

    private volatile boolean inited = true;

    protected Object output; //输出的结果对象

    protected BiConsumer<R, Response<C, R>> recycleListener;

    protected Filter<C, R, ? extends Response<C, R>> filter;

    protected Servlet<C, R, ? extends Response<C, R>> servlet;

    private final CompletionHandler finishHandler = new CompletionHandler<Integer, ByteBuffer>() {

        @Override
        public void completed(Integer result, ByteBuffer attachment) {
            if (attachment.hasRemaining()) {
                channel.write(attachment, attachment, this);
            } else {
                channel.offerBuffer(attachment);
                ByteBuffer data = request.removeMoredata();
                final boolean more = data != null && request.keepAlive;
                request.more = more;
                finish();
                if (more) new PrepareRunner(context, responsePool, request.channel, null, Response.this).run();
            }
        }

        @Override
        public void failed(Throwable exc, ByteBuffer attachment) {
            channel.offerBuffer(attachment);
            finish(true);
        }

    };

    private final CompletionHandler finishHandler2 = new CompletionHandler<Integer, ByteBuffer[]>() {

        @Override
        public void completed(final Integer result, final ByteBuffer[] attachments) {
            int index = -1;
            for (int i = 0; i < attachments.length; i++) {
                if (attachments[i].hasRemaining()) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                channel.write(attachments, index, attachments.length - index, attachments, this);
            } else {
                for (ByteBuffer attachment : attachments) {
                    channel.offerBuffer(attachment);
                }
                ByteBuffer data = request.removeMoredata();
                final boolean more = data != null && request.keepAlive;
                request.more = more;
                finish();
                if (more) new PrepareRunner(context, responsePool, request.channel, null, Response.this).run();
            }
        }

        @Override
        public void failed(Throwable exc, final ByteBuffer[] attachments) {
            for (ByteBuffer attachment : attachments) {
                channel.offerBuffer(attachment);
            }
            finish(true);
        }

    };

    protected Response(C context, final R request, ObjectPool<Response> responsePool) {
        this.context = context;
        this.request = request;
        this.responsePool = responsePool;
    }

    protected void offerBuffer(ByteBuffer... buffers) {
        for (ByteBuffer buffer : buffers) {
            channel.offerBuffer(buffer);
        }
    }

    protected AsyncConnection removeChannel() {
        AsyncConnection ch = this.channel;
        this.channel = null;
        this.request.channel = null;
        return ch;
    }

    protected void prepare() {
        inited = true;
    }

    protected boolean recycle() {
        if (!inited) return false;
        this.output = null;
        this.filter = null;
        this.servlet = null;
        request.recycle();
        if (channel != null) {
            channel.dispose();
            channel = null;
        }
        this.inited = false;
        return true;
    }

    protected void refuseAlive() {
        this.request.keepAlive = false;
    }

    protected void init(AsyncConnection channel) {
        this.channel = channel;
        this.request.channel = channel;
        this.request.createtime = System.currentTimeMillis();
    }

    protected void setFilter(Filter<C, R, Response<C, R>> filter) {
        this.filter = filter;
    }

    protected void thenEvent(Servlet servlet) {
        this.servlet = servlet;
    }

    @SuppressWarnings("unchecked")
    public void nextEvent() throws IOException {
        if (this.filter != null) {
            Filter runner = this.filter;
            this.filter = this.filter._next;
            runner.doFilter(request, this);
            return;
        }
        if (this.servlet != null) {
            Servlet s = this.servlet;
            this.servlet = null;
            s.execute(request, this);
        }
    }

    public void recycleListener(BiConsumer<R, Response<C, R>> recycleListener) {
        this.recycleListener = recycleListener;
    }

    public Object getOutput() {
        return output;
    }

    /**
     * 是否已关闭
     *
     * @return boolean
     */
    public boolean isClosed() {
        return !this.inited;
    }

    public void finish() {
        this.finish(false);
    }

    public void finish(boolean kill) {
        if (!this.inited) return; //避免重复关闭
        //System.println("耗时: " + (System.currentTimeMillis() - request.createtime));
        if (kill) refuseAlive();
        if (this.recycleListener != null) {
            try {
                this.recycleListener.accept(request, this);
            } catch (Exception e) {
                context.logger.log(Level.WARNING, "Response.recycleListener error, request = " + request, e);
            }
            this.recycleListener = null;
        }
        if (request.more) removeChannel();
        if (request.keepAlive && !request.more && channel != null) {
            if (channel.isOpen()) {
                AsyncConnection conn = removeChannel();
                this.recycle();
                this.prepare();
                new PrepareRunner(context, this.responsePool, conn, null, this).run();
            } else {
                channel.dispose();
            }
        } else {
            this.responsePool.accept(this);
        }
    }

    public void finish(final byte[] bs) {
        if (!this.inited) return; //避免重复关闭
        if (this.context.bufferCapacity == bs.length) {
            ByteBuffer buffer = channel.bufferSupplier.get();
            buffer.put(bs);
            buffer.flip();
            this.finish(buffer);
        } else {
            this.finish(ByteBuffer.wrap(bs));
        }
    }

    public void finish(ByteBuffer buffer) {
        if (!this.inited) return; //避免重复关闭
        final AsyncConnection conn = this.channel;
//        ByteBuffer data = this.request.removeMoredata();
//        final boolean more = data != null && this.request.keepAlive;
//        this.request.more = more;
        conn.write(buffer, buffer, finishHandler);
//        if (more) new PrepareRunner(this.context, this.responsePool, conn, data, null).run();
    }

    public void finish(boolean kill, ByteBuffer buffer) {
        if (!this.inited) return; //避免重复关闭
        if (kill) refuseAlive();
        final AsyncConnection conn = this.channel;
//        ByteBuffer data = this.request.removeMoredata();
//        final boolean more = data != null && this.request.keepAlive;
//        this.request.more = more;
        conn.write(buffer, buffer, finishHandler);
//        if (more) new PrepareRunner(this.context, this.responsePool, conn, data, null).run();
    }

    public void finish(ByteBuffer... buffers) {
        if (!this.inited) return; //避免重复关闭
        final AsyncConnection conn = this.channel;
//        ByteBuffer data = this.request.removeMoredata();
//        final boolean more = data != null && this.request.keepAlive;
//        this.request.more = more;
        conn.write(buffers, buffers, finishHandler2);
//        if (more) new PrepareRunner(this.context, this.responsePool, conn, data, null).run();
    }

    public void finish(boolean kill, ByteBuffer... buffers) {
        if (!this.inited) return; //避免重复关闭
        if (kill) refuseAlive();
        final AsyncConnection conn = this.channel;
        ByteBuffer data = this.request.removeMoredata();
        final boolean more = data != null && this.request.keepAlive;
        this.request.more = more;
        conn.write(buffers, buffers, finishHandler2);
        if (more) new PrepareRunner(this.context, this.responsePool, conn, data, null).run();
    }

    protected <A> void send(final ByteBuffer buffer, final A attachment, final CompletionHandler<Integer, A> handler) {
        this.channel.write(buffer, attachment, new CompletionHandler<Integer, A>() {

            @Override
            public void completed(Integer result, A attachment) {
                if (buffer.hasRemaining()) {
                    channel.write(buffer, attachment, this);
                } else {
                    channel.offerBuffer(buffer);
                    if (handler != null) handler.completed(result, attachment);
                }
            }

            @Override
            public void failed(Throwable exc, A attachment) {
                channel.offerBuffer(buffer);
                if (handler != null) handler.failed(exc, attachment);
            }

        });
    }

    protected <A> void send(final ByteBuffer[] buffers, A attachment, final CompletionHandler<Integer, A> handler) {
        this.channel.write(buffers, attachment, new CompletionHandler<Integer, A>() {

            @Override
            public void completed(Integer result, A attachment) {
                int index = -1;
                for (int i = 0; i < buffers.length; i++) {
                    if (buffers[i].hasRemaining()) {
                        index = i;
                        break;
                    }
                    channel.offerBuffer(buffers[i]);
                }
                if (index == 0) {
                    channel.write(buffers, attachment, this);
                } else if (index > 0) {
                    ByteBuffer[] newattachs = new ByteBuffer[buffers.length - index];
                    System.arraycopy(buffers, index, newattachs, 0, newattachs.length);
                    channel.write(newattachs, attachment, this);
                } else if (handler != null) handler.completed(result, attachment);
            }

            @Override
            public void failed(Throwable exc, A attachment) {
                for (ByteBuffer buffer : buffers) {
                    channel.offerBuffer(buffer);
                }
                if (handler != null) handler.failed(exc, attachment);
            }

        });
    }

    public C getContext() {
        return context;
    }
}
