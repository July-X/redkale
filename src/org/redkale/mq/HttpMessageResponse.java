/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.mq;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.*;
import java.util.logging.Level;
import org.redkale.convert.*;
import org.redkale.net.http.*;
import org.redkale.service.RetResult;
import static org.redkale.mq.MessageRecord.CTYPE_HTTP_RESULT;
import org.redkale.net.Response;

/**
 *
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 *
 * @since 2.1.0
 */
public class HttpMessageResponse extends HttpResponse {

    protected final HttpMessageClient messageClient;

    protected MessageRecord message;

    protected MessageProducer producer;

    protected boolean finest;

    protected Runnable callback;

    public HttpMessageResponse(HttpContext context, HttpMessageClient messageClient, final Supplier<HttpMessageResponse> respSupplier, final Consumer<HttpMessageResponse> respConsumer) {
        super(context, new HttpMessageRequest(context), null);
        this.responseSupplier = (Supplier) respSupplier;
        this.responseConsumer = (Consumer) respConsumer;
        this.messageClient = messageClient;
    }

//    public HttpMessageResponse(HttpContext context, HttpMessageRequest request, Runnable callback,
//        HttpResponseConfig config, HttpMessageClient messageClient, MessageProducer producer) {
//        super(context, request, config);
//        this.message = request.message;
//        this.callback = callback;
//        this.messageClient = messageClient;
//        this.producer = producer;
//        this.finest = producer.logger.isLoggable(Level.FINEST);
//    }

    public void prepare(MessageRecord message, Runnable callback, MessageProducer producer) {
        ((HttpMessageRequest)request).prepare(message);
        this.message = message;
        this.callback = callback;
        this.producer = producer;
        this.finest = producer.logger.isLoggable(Level.FINEST);
    }

    public HttpMessageRequest request() {
        return (HttpMessageRequest) request;
    }

    public void finishHttpResult(HttpResult result) {
        finishHttpResult(this.finest, ((HttpMessageRequest) this.request).getRespConvert(), this.message, this.callback, this.messageClient, this.producer, message.getResptopic(), result);
    }

    public static void finishHttpResult(boolean finest, Convert respConvert, MessageRecord msg, Runnable callback, MessageClient messageClient, MessageProducer producer, String resptopic, HttpResult result) {
        if (callback != null) callback.run();
        if (resptopic == null || resptopic.isEmpty()) return;
        if (result.getResult() instanceof RetResult) {
            RetResult ret = (RetResult) result.getResult();
            //必须要塞入retcode， 开发者可以无需反序列化ret便可确定操作是否返回成功
            if (!ret.isSuccess()) result.header("retcode", String.valueOf(ret.getRetcode()));
        }
        if (result.convert() == null && respConvert != null) result.convert(respConvert);
        if (finest) {
            Object innerrs = result.getResult();
            if (innerrs instanceof byte[]) innerrs = new String((byte[]) innerrs, StandardCharsets.UTF_8);
            producer.logger.log(Level.FINEST, "HttpMessageResponse.finishHttpResult seqid=" + msg.getSeqid() + ", content: " + innerrs + ", status: " + result.getStatus() + ", headers: " + result.getHeaders());
        }
        byte[] content = HttpResultCoder.getInstance().encode(result);
        producer.apply(messageClient.createMessageRecord(msg.getSeqid(), CTYPE_HTTP_RESULT, resptopic, null, content));
    }

    @Override
    protected void prepare() {
        super.prepare();
    }

    @Override
    protected boolean recycle() {
        Supplier<Response> respSupplier = this.responseSupplier;
        Consumer<Response> respConsumer = this.responseConsumer;
        boolean rs = super.recycle();
        this.responseSupplier = respSupplier;
        this.responseConsumer = respConsumer;
        this.message = null;
        this.producer = null;
        this.callback = null;
        this.finest = false;
        return rs;
    }

    @Override
    public void finishJson(org.redkale.service.RetResult ret) {
        if (message.isEmptyResptopic()) {
            if (callback != null) callback.run();
            return;
        }
        finishHttpResult(new HttpResult(ret.clearConvert(), ret));
    }

    @Override
    public void finish(String obj) {
        if (message.isEmptyResptopic()) {
            if (callback != null) callback.run();
            return;
        }
        finishHttpResult(new HttpResult(obj == null ? "" : obj));
    }

    @Override
    public void finish404() {
        finish(404, null);
    }

    @Override
    public void finish(int status, String msg) {
        if (status > 400) {
            producer.logger.log(Level.WARNING, "HttpMessageResponse.finish status: " + status + ", message: " + this.message);
        } else if (finest) {
            producer.logger.log(Level.FINEST, "HttpMessageResponse.finish status: " + status);
        }
        if (this.message.isEmptyResptopic()) {
            if (callback != null) callback.run();
            return;
        }
        finishHttpResult(new HttpResult(msg == null ? "" : msg).status(status));
    }

    @Override
    public void finish(final Convert convert, HttpResult result) {
        if (message.isEmptyResptopic()) {
            if (callback != null) callback.run();
            return;
        }
        if (convert != null) result.convert(convert);
        finishHttpResult(result);
    }

    @Override
    public void finish(boolean kill, final byte[] bs, int offset, int length) {
        if (message.isEmptyResptopic()) {
            if (callback != null) callback.run();
            return;
        }
        if (offset == 0 && bs.length == length) {
            finishHttpResult(new HttpResult(bs));
        } else {
            finishHttpResult(new HttpResult(Arrays.copyOfRange(bs, offset, offset + length)));
        }
    }

    @Override
    public void finish(boolean kill, final String contentType, final byte[] bs, int offset, int length) {
        if (message.isEmptyResptopic()) {
            if (callback != null) callback.run();
            return;
        }
        byte[] rs = (offset == 0 && bs.length == length) ? bs : Arrays.copyOfRange(bs, offset, offset + length);
        finishHttpResult(new HttpResult(rs).contentType(contentType));
    }

    @Override
    public void finish(boolean kill, ByteBuffer buffer) {
        if (message.isEmptyResptopic()) {
            if (callback != null) callback.run();
            return;
        }
        byte[] bs = new byte[buffer.remaining()];
        buffer.get(bs);
        finishHttpResult(new HttpResult(bs));
    }

    @Override
    public void finish(boolean kill, ByteBuffer... buffers) {
        if (message.isEmptyResptopic()) {
            if (callback != null) callback.run();
            return;
        }
        int size = 0;
        for (ByteBuffer buf : buffers) {
            size += buf.remaining();
        }
        byte[] bs = new byte[size];
        int index = 0;
        for (ByteBuffer buf : buffers) {
            int r = buf.remaining();
            buf.get(bs, index, r);
            index += r;
        }
        finishHttpResult(new HttpResult(bs));
    }

}
