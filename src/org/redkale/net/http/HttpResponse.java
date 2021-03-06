/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net.http;

import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.ZoneId;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import java.util.logging.Level;
import org.redkale.convert.*;
import org.redkale.convert.json.JsonConvert;
import org.redkale.net.*;
import org.redkale.util.AnyValue.DefaultAnyValue;
import org.redkale.util.AnyValue.Entry;
import org.redkale.util.*;
import static org.redkale.util.Utility.append;

/**
 * Http响应包 与javax.servlet.http.HttpServletResponse 基本类似。  <br>
 * 同时提供发送json的系列接口: public void finishJson(Type type, Object obj)  <br>
 * Redkale提倡http+json的接口风格， 所以主要输出的数据格式为json， 同时提供异步接口。  <br>
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
public class HttpResponse extends Response<HttpContext, HttpRequest> {

    protected static final byte[] bytes304 = "HTTP/1.1 304 Not Modified\r\nContent-Length:0\r\n\r\n".getBytes();

    protected static final byte[] bytes404 = "HTTP/1.1 404 Not Found\r\nContent-Length:0\r\n\r\n".getBytes();

    protected static final byte[] status200Bytes = "HTTP/1.1 200 OK\r\n".getBytes();

    protected static final byte[] LINE = new byte[]{'\r', '\n'};

    protected static final byte[] serverNameBytes = ("Server: " + System.getProperty("http.response.header.server", "redkale" + "/" + Redkale.getDotedVersion()) + "\r\n").getBytes();

    protected static final byte[] connectCloseBytes = "none".equalsIgnoreCase(System.getProperty("http.response.header.connection")) ? new byte[0] : "Connection: close\r\n".getBytes();

    protected static final byte[] connectAliveBytes = "none".equalsIgnoreCase(System.getProperty("http.response.header.connection")) ? new byte[0] : "Connection: keep-alive\r\n".getBytes();

    private static final int cacheMaxContentLength = 999;

    private static final byte[] status200_server_live_Bytes = append(append(status200Bytes, serverNameBytes), connectAliveBytes);

    private static final byte[] status200_server_close_Bytes = append(append(status200Bytes, serverNameBytes), connectCloseBytes);

    private static final ZoneId ZONE_GMT = ZoneId.of("GMT");

    private static final OpenOption[] options = new OpenOption[]{StandardOpenOption.READ};

    private static final Map<Integer, String> httpCodes = new HashMap<>();

    private static final Map<Integer, byte[]> contentLengthMap = new HashMap<>();

    static {

        httpCodes.put(100, "Continue");
        httpCodes.put(101, "Switching Protocols");

        httpCodes.put(200, "OK");
        httpCodes.put(201, "Created");
        httpCodes.put(202, "Accepted");
        httpCodes.put(203, "Non-Authoritative Information");
        httpCodes.put(204, "No Content");
        httpCodes.put(205, "Reset Content");
        httpCodes.put(206, "Partial Content");

        httpCodes.put(300, "Multiple Choices");
        httpCodes.put(301, "Moved Permanently");
        httpCodes.put(302, "Found");
        httpCodes.put(303, "See Other");
        httpCodes.put(304, "Not Modified");
        httpCodes.put(305, "Use Proxy");
        httpCodes.put(307, "Temporary Redirect");

        httpCodes.put(400, "Bad Request");
        httpCodes.put(401, "Unauthorized");
        httpCodes.put(402, "Payment Required");
        httpCodes.put(403, "Forbidden");
        httpCodes.put(404, "Not Found");
        httpCodes.put(405, "Method Not Allowed");
        httpCodes.put(406, "Not Acceptable");
        httpCodes.put(407, "Proxy Authentication Required");
        httpCodes.put(408, "Request Timeout");
        httpCodes.put(409, "Conflict");
        httpCodes.put(410, "Gone");
        httpCodes.put(411, "Length Required");
        httpCodes.put(412, "Precondition Failed");
        httpCodes.put(413, "Request Entity Too Large");
        httpCodes.put(414, "Request URI Too Long");
        httpCodes.put(415, "Unsupported Media Type");
        httpCodes.put(416, "Requested Range Not Satisfiable");
        httpCodes.put(417, "Expectation Failed");

        httpCodes.put(500, "Internal Server Error");
        httpCodes.put(501, "Not Implemented");
        httpCodes.put(502, "Bad Gateway");
        httpCodes.put(503, "Service Unavailable");
        httpCodes.put(504, "Gateway Timeout");
        httpCodes.put(505, "HTTP Version Not Supported");

        for (int i = 0; i <= cacheMaxContentLength; i++) {
            contentLengthMap.put(i, ("Content-Length: " + i + "\r\n").getBytes());
        }
    }

    private int status = 200;

    private String contentType = "";

    private long contentLength = -1;

    private HttpCookie[] cookies;

    private boolean respHeadContainsConnection;

    private int headWritedSize = -1; //0表示跳过header，正数表示header的字节长度。

    private BiConsumer<HttpResponse, byte[]> cacheHandler;

    private BiFunction<HttpRequest, org.redkale.service.RetResult, org.redkale.service.RetResult> retResultHandler;

    private Map<Integer, byte[]> lastContentLengthMap;//lazyHeaders=true下缓存, recycle不会清空

    private int lastContentLength; //lazyHeaders=true下缓存, recycle不会清空

    private byte[] lastContentLengthBytes; //lazyHeaders=true下缓存, recycle不会清空

    //private Supplier<ByteBuffer> bodyBufferSupplier;
    //------------------------------------------------
    private final String plainContentType;

    private final byte[] plainContentTypeBytes;

    private final String jsonContentType;

    private final byte[] jsonContentTypeBytes;

    private final DefaultAnyValue header = new DefaultAnyValue();

    private final String[][] defaultAddHeaders;

    private final String[][] defaultSetHeaders;

    private final boolean autoOptions;

    private final Supplier<byte[]> dateSupplier;

    private final HttpCookie defaultCookie;

    private final List<HttpRender> renders;

    private final boolean hasRender;

    private final HttpRender onlyoneHttpRender;

    private final ByteArray headerArray = new ByteArray();

    private final Map<Integer, byte[]> plainLiveContentLengthMap;

    private final Map<Integer, byte[]> jsonLiveContentLengthMap;

    private final Map<Integer, byte[]> plainCloseContentLengthMap;

    private final Map<Integer, byte[]> jsonCloseContentLengthMap;

    protected final CompletionHandler<Integer, Void> pipelineWriteHandler = new CompletionHandler<Integer, Void>() {

        @Override
        public void completed(Integer result, Void attachment) {
            finish();
        }

        @Override
        public void failed(Throwable exc, Void attachment) {
            finish(true);
        }
    };

    @SuppressWarnings("Convert2Lambda")
    protected final ConvertBytesHandler convertHandler = new ConvertBytesHandler() {
        @Override
        public <A> void completed(byte[] bs, int offset, int length, Consumer<A> callback, A attachment) {
            finish(bs, offset, length, callback, attachment);
        }
    };

    public HttpResponse(HttpContext context, HttpRequest request, HttpResponseConfig config) {
        super(context, request);
        this.defaultAddHeaders = config == null ? null : config.defaultAddHeaders;
        this.defaultSetHeaders = config == null ? null : config.defaultSetHeaders;
        this.defaultCookie = config == null ? null : config.defaultCookie;
        this.autoOptions = config == null ? false : config.autoOptions;
        this.dateSupplier = config == null ? null : config.dateSupplier;
        this.renders = config == null ? null : config.renders;
        this.hasRender = renders != null && !renders.isEmpty();
        this.onlyoneHttpRender = renders != null && renders.size() == 1 ? renders.get(0) : null;

        this.plainContentType = config == null ? "text/plain; charset=utf-8" : config.plainContentType;
        this.jsonContentType = config == null ? "application/json; charset=utf-8" : config.jsonContentType;
        this.plainContentTypeBytes = config == null ? ("Content-Type: " + this.plainContentType + "\r\n").getBytes() : config.plainContentTypeBytes;
        this.jsonContentTypeBytes = config == null ? ("Content-Type: " + this.jsonContentType + "\r\n").getBytes() : config.jsonContentTypeBytes;
        this.plainLiveContentLengthMap = config == null ? new HashMap<>() : config.plainLiveContentLengthMap;
        this.plainCloseContentLengthMap = config == null ? new HashMap<>() : config.plainCloseContentLengthMap;
        this.jsonLiveContentLengthMap = config == null ? new HashMap<>() : config.jsonLiveContentLengthMap;
        this.jsonCloseContentLengthMap = config == null ? new HashMap<>() : config.jsonCloseContentLengthMap;
        this.contentType = this.plainContentType;
    }

    @Override
    protected AsyncConnection removeChannel() {
        return super.removeChannel();
    }

    protected AsyncConnection getChannel() {
        return channel;
    }

    @Override
    protected void prepare() {
        super.prepare();
    }

    @Override
    protected boolean recycle() {
        this.status = 200;
        this.contentLength = -1;
        this.contentType = null;
        this.cookies = null;
        this.headWritedSize = -1;
        //this.headBuffer = null;
        this.header.clear();
        this.headerArray.clear();
        this.cacheHandler = null;
        this.retResultHandler = null;
        this.respHeadContainsConnection = false;
        return super.recycle();
    }

//    protected Supplier<ByteBuffer> getBodyBufferSupplier() {
//        return bodyBufferSupplier;
//    }
    @Override
    protected void init(AsyncConnection channel) {
        super.init(channel);
    }

    /**
     * 获取状态码对应的状态描述
     *
     * @param status 状态码
     *
     * @return 状态描述
     */
    protected String getHttpCode(int status) {
        return httpCodes.get(status);
    }

    protected HttpRequest getRequest() {
        return request;
    }

    protected String getHttpCode(int status, String defValue) {
        String v = httpCodes.get(status);
        return v == null ? defValue : v;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void thenEvent(Servlet servlet) {
        this.servlet = servlet;
    }

    protected boolean isAutoOptions() {
        return this.autoOptions;
    }

    /**
     * 增加Cookie值
     *
     * @param cookies cookie
     *
     * @return HttpResponse
     */
    public HttpResponse addCookie(HttpCookie... cookies) {
        this.cookies = Utility.append(this.cookies, cookies);
        return this;
    }

    /**
     * 增加Cookie值
     *
     * @param cookies cookie
     *
     * @return HttpResponse
     */
    public HttpResponse addCookie(Collection<HttpCookie> cookies) {
        this.cookies = Utility.append(this.cookies, cookies);
        return this;
    }

    /**
     * 创建CompletionHandler实例
     *
     * @return CompletionHandler
     */
    public CompletionHandler createAsyncHandler() {
        return Utility.createAsyncHandler((v, a) -> {
            finish(v);
        }, (t, a) -> {
            context.getLogger().log(Level.WARNING, "Servlet occur, force to close channel. request = " + request + ", result is CompletionHandler", (Throwable) t);
            finish(500, null);
        });
    }

    /**
     * 创建CompletionHandler子类的实例 <br>
     *
     * 传入的CompletionHandler子类必须是public，且保证其子类可被继承且completed、failed可被重载且包含空参数的构造函数。
     *
     * @param <H>          泛型
     * @param handlerClass CompletionHandler子类
     *
     * @return CompletionHandler
     */
    @SuppressWarnings("unchecked")
    public <H extends CompletionHandler> H createAsyncHandler(Class<H> handlerClass) {
        if (handlerClass == null || handlerClass == CompletionHandler.class) return (H) createAsyncHandler();
        return context.loadAsyncHandlerCreator(handlerClass).create(createAsyncHandler());
    }

    /**
     * 将对象以JSON格式输出
     *
     * @param obj 输出对象
     */
    public void finishJson(final Object obj) {
        this.contentType = this.jsonContentType;
        if (this.recycleListener != null) this.output = obj;
        request.getRespConvert().convertToBytes(obj, convertHandler);
    }

    /**
     * 指定json内容长度将对象以JSON格式输出, 临时功能
     *
     * @param length json内容长度
     * @param obj    输出对象
     */
    @Deprecated
    public void finishJson(final int length, final Object obj) {
        this.contentType = this.jsonContentType;
        this.contentLength = length;
        if (this.recycleListener != null) this.output = obj;
        createHeader();
        ByteArray data = headerArray;
        request.getRespConvert().convertToBytes(data, obj);

        int pipelineIndex = request.getPipelineIndex();
        if (pipelineIndex > 0) {
            boolean over = this.channel.writePipelineData(pipelineIndex, request.getPipelineCount(), data);
            if (over) {
                request.setPipelineOver(true);
                this.channel.flushPipelineData(this.pipelineWriteHandler);
            } else {
                removeChannel();
                this.responseConsumer.accept(this);
            }
        } else {
            if (this.channel.hasPipelineData()) {
                this.channel.writePipelineData(pipelineIndex, request.getPipelineCount(), data);
                this.channel.flushPipelineData(this.pipelineWriteHandler);
            } else {
                //不能用finish(boolean kill, final ByteTuple array) 否则会调this.finish
                super.finish(false, data.content(), 0, data.length());
            }
        }
    }

    /**
     * 将对象以JSON格式输出
     *
     * @param convert 指定的JsonConvert
     * @param obj     输出对象
     */
    public void finishJson(final JsonConvert convert, final Object obj) {
        this.contentType = this.jsonContentType;
        if (this.recycleListener != null) this.output = obj;
        convert.convertToBytes(obj, convertHandler);
    }

    /**
     * 将对象以JSON格式输出
     *
     * @param type 指定的类型
     * @param obj  输出对象
     */
    public void finishJson(final Type type, final Object obj) {
        this.contentType = this.jsonContentType;
        if (this.recycleListener != null) this.output = obj;
        request.getRespConvert().convertToBytes(type, obj, convertHandler);
    }

    /**
     * 将对象以JSON格式输出
     *
     * @param convert 指定的JsonConvert
     * @param type    指定的类型
     * @param obj     输出对象
     */
    public void finishJson(final JsonConvert convert, final Type type, final Object obj) {
        this.contentType = this.jsonContentType;
        if (this.recycleListener != null) this.output = obj;
        convert.convertToBytes(type, obj, convertHandler);
    }

    /**
     * 将对象以JSON格式输出
     *
     * @param objs 输出对象
     */
    @Deprecated  //@since 2.3.0
    void finishJson(final Object... objs) {
        this.contentType = this.jsonContentType;
        if (this.recycleListener != null) this.output = objs;
        request.getRespConvert().convertToBytes(objs, convertHandler);
    }

    /**
     * 将RetResult对象以JSON格式输出
     *
     * @param ret RetResult输出对象
     */
    public void finishJson(org.redkale.service.RetResult ret) {
        this.contentType = this.jsonContentType;
        if (this.retResultHandler != null) {
            ret = this.retResultHandler.apply(this.request, ret);
        }
        if (this.recycleListener != null) this.output = ret;
        if (ret != null && !ret.isSuccess()) {
            this.header.addValue("retcode", String.valueOf(ret.getRetcode()));
            this.header.addValue("retinfo", ret.getRetinfo());
        }
        Convert convert = ret == null ? null : ret.convert();
        if (convert == null) convert = request.getRespConvert();
        convert.convertToBytes(ret, convertHandler);
    }

    /**
     * 将RetResult对象以JSON格式输出
     *
     * @param convert 指定的JsonConvert
     * @param ret     RetResult输出对象
     */
    public void finishJson(final JsonConvert convert, org.redkale.service.RetResult ret) {
        this.contentType = this.jsonContentType;
        if (this.retResultHandler != null) {
            ret = this.retResultHandler.apply(this.request, ret);
        }
        if (this.recycleListener != null) this.output = ret;
        if (ret != null && !ret.isSuccess()) {
            this.header.addValue("retcode", String.valueOf(ret.getRetcode()));
            this.header.addValue("retinfo", ret.getRetinfo());
        }
        convert.convertToBytes(ret, convertHandler);
    }

    /**
     * 将HttpResult对象输出
     *
     * @param convert 指定的Convert
     * @param result  HttpResult输出对象
     */
    public void finish(final Convert convert, HttpResult result) {
        if (result.getContentType() != null) setContentType(result.getContentType());
        addHeader(result.getHeaders()).addCookie(result.getCookies()).setStatus(result.getStatus() < 1 ? 200 : result.getStatus());
        if (result.getResult() == null) {
            finish("");
        } else if (result.getResult() instanceof CharSequence) {
            finish(result.getResult().toString());
        } else {
            Convert cc = result.convert();
            if (cc == null) cc = convert;
            finish(cc, result.getResult());
        }
    }

    /**
     * 将CompletableFuture的结果对象以JSON格式输出
     *
     * @param future 输出对象的句柄
     */
    public void finishJson(final CompletableFuture future) {
        finish(request.getRespConvert(), (Type) null, future);
    }

    /**
     * 将CompletableFuture的结果对象以JSON格式输出
     *
     * @param convert 指定的JsonConvert
     * @param future  输出对象的句柄
     */
    @SuppressWarnings("unchecked")
    public void finishJson(final JsonConvert convert, final CompletableFuture future) {
        finish(convert, (Type) null, future);
    }

    /**
     * 将CompletableFuture的结果对象以JSON格式输出
     *
     * @param convert 指定的JsonConvert
     * @param type    指定的类型
     * @param future  输出对象的句柄
     */
    @SuppressWarnings("unchecked")
    public void finishJson(final JsonConvert convert, final Type type, final CompletableFuture future) {
        finish(convert, type, future);
    }

    /**
     * 将结果对象输出
     *
     * @param obj 输出对象
     */
    @SuppressWarnings("unchecked")
    public void finish(final Object obj) {
        finish(request.getRespConvert(), (Type) null, obj);
    }

    /**
     * 将结果对象输出
     *
     * @param convert 指定的Convert
     * @param obj     输出对象
     */
    @SuppressWarnings("unchecked")
    public void finish(final Convert convert, final Object obj) {
        finish(convert, (Type) null, obj);
    }

    /**
     * 将结果对象输出
     *
     * @param convert 指定的Convert
     * @param type    指定的类型
     * @param obj     输出对象
     */
    @SuppressWarnings("unchecked")
    public void finish(final Convert convert, final Type type, Object obj) {
        //以下if条件会被Rest类第1900行左右的地方用到
        if (obj == null) {
            finish("null");
        } else if (obj instanceof CompletableFuture) {
            ((CompletableFuture) obj).whenComplete((v, e) -> {
                if (e != null) {
                    context.getLogger().log(Level.WARNING, "Servlet occur, force to close channel. request = " + request + ", result is CompletableFuture", (Throwable) e);
                    finish(500, null);
                    return;
                }
                finish(convert, type, v);
            });
        } else if (obj instanceof CharSequence) {
            finish((String) obj.toString());
        } else if (obj instanceof byte[]) {
            finish((byte[]) obj);
        } else if (obj instanceof File) {
            try {
                finish((File) obj);
            } catch (IOException e) {
                context.getLogger().log(Level.WARNING, "HttpServlet finish File occur, force to close channel. request = " + getRequest(), e);
                finish(500, null);
            }
        } else if (obj instanceof org.redkale.service.RetResult) {
            finishJson((org.redkale.service.RetResult) obj);
        } else if (obj instanceof HttpResult) {
            finish(convert, (HttpResult) obj);
        } else {
            if (hasRender) {
                if (onlyoneHttpRender != null) {
                    if (onlyoneHttpRender.getType().isAssignableFrom(obj.getClass())) {
                        onlyoneHttpRender.renderTo(this.request, this, convert, obj);
                        return;
                    }
                } else {
                    Class objt = obj.getClass();
                    for (HttpRender render : this.renders) {
                        if (render.getType().isAssignableFrom(objt)) {
                            render.renderTo(this.request, this, convert, obj);
                            return;
                        }
                    }
                }
            }
            if (convert instanceof JsonConvert) {
                this.contentType = this.jsonContentType;
            } else if (convert instanceof TextConvert) {
                this.contentType = this.plainContentType;
            }
            if (this.recycleListener != null) this.output = obj;
            if (obj instanceof org.redkale.service.RetResult) {
                org.redkale.service.RetResult ret = (org.redkale.service.RetResult) obj;
                if (this.retResultHandler != null) {
                    ret = this.retResultHandler.apply(this.request, ret);
                    obj = ret;
                }
                if (!ret.isSuccess()) {
                    this.header.addValue("retcode", String.valueOf(ret.getRetcode())).addValue("retinfo", ret.getRetinfo());
                }
            }
            //this.channel == null为虚拟的HttpResponse
            if (type == null) {
                convert.convertToBytes(obj, convertHandler);
            } else {
                convert.convertToBytes(type, obj, convertHandler);
            }
        }
    }

    /**
     * 将指定字符串以响应结果输出
     *
     * @param obj 输出内容
     */
    public void finish(String obj) {
        finish(200, obj);
    }

    /**
     * 以指定响应码附带内容输出
     *
     * @param status  响应码
     * @param message 输出内容
     */
    public void finish(int status, String message) {
        if (isClosed()) return;
        this.status = status;
        if (status != 200) super.refuseAlive();
        final byte[] val = message == null ? new byte[0] : (context.getCharset() == null ? Utility.encodeUTF8(message) : message.getBytes(context.getCharset()));
        finish(false, null, val, 0, val.length, null, null);
    }

    @Override
    public void finish(boolean kill, final byte[] bs, int offset, int length) {
        finish(false, null, bs, offset, length, null, null);
    }

    public <A> void finish(final byte[] bs, int offset, int length, Consumer<A> callback, A attachment) {
        finish(false, null, bs, offset, length, callback, attachment);
    }

    /**
     * 将指定byte[]按响应结果输出
     *
     * @param contentType ContentType
     * @param bs          输出内容
     */
    public void finish(final String contentType, final byte[] bs) {
        finish(false, contentType, bs, 0, bs == null ? 0 : bs.length, null, null);
    }

    /**
     * 将指定byte[]按响应结果输出
     *
     * @param kill        kill
     * @param contentType ContentType
     * @param bs          输出内容
     * @param offset      偏移量
     * @param length      长度
     */
    protected void finish(boolean kill, final String contentType, final byte[] bs, int offset, int length) {
        finish(kill, contentType, bs, offset, length, null, null);
    }

    /**
     * 将指定byte[]按响应结果输出
     *
     * @param kill        kill
     * @param contentType ContentType
     * @param bs          输出内容
     * @param offset      偏移量
     * @param length      长度
     * @param callback    Consumer
     * @param attachment  ConvertWriter
     * @param <A>         A
     */
    protected <A> void finish(boolean kill, final String contentType, final byte[] bs, int offset, int length, Consumer<A> callback, A attachment) {
        if (isClosed()) return; //避免重复关闭
        ByteArray data = headerArray;
        if (this.headWritedSize < 0) {
            if (contentType != null) this.contentType = contentType;
            this.contentLength = length;
            createHeader();
        }
        data.put(bs, offset, length);
        if (callback != null) callback.accept(attachment);
        if (cacheHandler != null) cacheHandler.accept(this, data.getBytes());

        int pipelineIndex = request.getPipelineIndex();
        if (pipelineIndex > 0) {
            boolean over = this.channel.writePipelineData(pipelineIndex, request.getPipelineCount(), data);
            if (over) {
                request.setPipelineOver(true);
                this.channel.flushPipelineData(this.pipelineWriteHandler);
            } else {
                removeChannel();
                this.responseConsumer.accept(this);
            }
        } else {
            if (this.channel.hasPipelineData()) {
                this.channel.writePipelineData(pipelineIndex, request.getPipelineCount(), data);
                this.channel.flushPipelineData(this.pipelineWriteHandler);
            } else {
                //不能用finish(boolean kill, final ByteTuple array) 否则会调this.finish
                super.finish(false, data.content(), 0, data.length());
            }
        }
    }

    /**
     * 以304状态码输出
     */
    public void finish304() {
        skipHeader();
        super.finish(false, bytes304);
    }

    /**
     * 以404状态码输出
     */
    public void finish404() {
        skipHeader();
        super.finish(false, bytes404);
    }

    //Header大小
    protected void createHeader() {
        if (this.status == 200 && !this.respHeadContainsConnection && !this.request.isWebSocket()
            && (this.contentType == null || this.contentType == this.jsonContentType || this.contentType == this.plainContentType)
            && (this.contentLength >= 0 && this.contentLength < jsonLiveContentLengthMap.size())) {
            Map<Integer, byte[]> lengthMap = this.plainLiveContentLengthMap;
            if (this.request.isKeepAlive()) {
                if (this.contentType == this.jsonContentType) {
                    lengthMap = this.jsonLiveContentLengthMap;
                }
            } else {
                if (this.contentType == this.jsonContentType) {
                    lengthMap = this.jsonCloseContentLengthMap;
                } else {
                    lengthMap = this.plainCloseContentLengthMap;
                }
            }
            if (context.lazyHeaders) {
                if (this.lastContentLength == this.contentLength && this.lastContentLengthMap == lengthMap) {
                    headerArray.put(this.lastContentLengthBytes);
                } else {
                    byte[] lenbs = lengthMap.get((int) this.contentLength);
                    this.lastContentLength = (int) this.contentLength;
                    this.lastContentLengthMap = lengthMap;
                    this.lastContentLengthBytes = lenbs;
                    headerArray.put(lenbs);
                }
            } else {
                headerArray.put(lengthMap.get((int) this.contentLength));
            }
        } else {
            if (this.status == 200 && !this.respHeadContainsConnection && !this.request.isWebSocket()) {
                if (this.request.isKeepAlive()) {
                    headerArray.put(status200_server_live_Bytes);
                } else {
                    headerArray.put(status200_server_close_Bytes);
                }
            } else {
                if (this.status == 200) {
                    headerArray.put(status200Bytes);
                } else {
                    headerArray.put(("HTTP/1.1 " + this.status + " " + httpCodes.get(this.status) + "\r\n").getBytes());
                }
                headerArray.put(serverNameBytes);
                if (!this.respHeadContainsConnection) {
                    headerArray.put(this.request.isKeepAlive() ? connectAliveBytes : connectCloseBytes);
                }
            }
            if (!this.request.isWebSocket()) {
                if (this.contentType == this.jsonContentType) {
                    headerArray.put(this.jsonContentTypeBytes);
                } else if (this.contentType == null || this.contentType == this.plainContentType) {
                    headerArray.put(this.plainContentTypeBytes);
                } else {
                    headerArray.put(("Content-Type: " + this.contentType + "\r\n").getBytes());
                }
            }
            if (this.contentLength >= 0) {
                if (this.contentLength < contentLengthMap.size()) {
                    headerArray.put(contentLengthMap.get((int) this.contentLength));
                } else {
                    headerArray.put(("Content-Length: " + this.contentLength + "\r\n").getBytes());
                }
            }
        }
        if (dateSupplier != null) headerArray.put(dateSupplier.get());

        if (this.defaultAddHeaders != null) {
            for (String[] headers : this.defaultAddHeaders) {
                if (headers.length > 3) {
                    String v = request.getParameter(headers[2]);
                    if (v != null) this.header.addValue(headers[0], v);
                } else if (headers.length > 2) {
                    String v = request.getHeader(headers[2]);
                    if (v != null) this.header.addValue(headers[0], v);
                } else {
                    this.header.addValue(headers[0], headers[1]);
                }
            }
        }
        if (this.defaultSetHeaders != null) {
            for (String[] headers : this.defaultSetHeaders) {
                if (headers.length > 3) {
                    String v = request.getParameter(headers[2]);
                    if (v != null) this.header.setValue(headers[0], v);
                } else if (headers.length > 2) {
                    String v = request.getHeader(headers[2]);
                    if (v != null) this.header.setValue(headers[0], v);
                } else {
                    this.header.setValue(headers[0], headers[1]);
                }
            }
        }
        for (Entry<String> en : this.header.getStringEntrys()) {
            headerArray.put((en.name + ": " + en.getValue() + "\r\n").getBytes());
        }
        if (request.newsessionid != null) {
            String domain = defaultCookie == null ? null : defaultCookie.getDomain();
            if (domain == null || domain.isEmpty()) {
                domain = "";
            } else {
                domain = "Domain=" + domain + "; ";
            }
            String path = defaultCookie == null ? null : defaultCookie.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (request.newsessionid.isEmpty()) {
                headerArray.put(("Set-Cookie: " + HttpRequest.SESSIONID_NAME + "=; " + domain + "Path=/; Max-Age=0; HttpOnly\r\n").getBytes());
            } else {
                headerArray.put(("Set-Cookie: " + HttpRequest.SESSIONID_NAME + "=" + request.newsessionid + "; " + domain + "Path=/; HttpOnly\r\n").getBytes());
            }
        }
        if (this.cookies != null) {
            for (HttpCookie cookie : this.cookies) {
                if (cookie == null) continue;
                if (defaultCookie != null) {
                    if (defaultCookie.getDomain() != null && cookie.getDomain() == null) cookie.setDomain(defaultCookie.getDomain());
                    if (defaultCookie.getPath() != null && cookie.getPath() == null) cookie.setPath(defaultCookie.getPath());
                }
                headerArray.put(("Set-Cookie: " + cookieString(cookie) + "\r\n").getBytes());
            }
        }
        headerArray.put(LINE);
        this.headWritedSize = headerArray.length();
    }

    private CharSequence cookieString(HttpCookie cookie) {
        StringBuilder sb = new StringBuilder();
        sb.append(cookie.getName()).append("=").append(cookie.getValue()).append("; Version=1");
        if (cookie.getDomain() != null) sb.append("; Domain=").append(cookie.getDomain());
        if (cookie.getPath() != null) sb.append("; Path=").append(cookie.getPath());
        if (cookie.getPortlist() != null) sb.append("; Port=").append(cookie.getPortlist());
        if (cookie.getMaxAge() > 0) {
            sb.append("; Max-Age=").append(cookie.getMaxAge());
            sb.append("; Expires=").append(RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now(ZONE_GMT).plusSeconds(cookie.getMaxAge())));
        }
        if (cookie.getSecure()) sb.append("; Secure");
        if (cookie.isHttpOnly()) sb.append("; HttpOnly");
        return sb;
    }

    /**
     * 异步输出指定内容
     *
     * @param <A>     泛型
     * @param buffer  输出内容
     * @param handler 异步回调函数
     */
    protected <A> void sendBody(ByteBuffer buffer, CompletionHandler<Integer, Void> handler) {
        if (this.headWritedSize < 0) {
            if (this.contentLength < 0) this.contentLength = buffer == null ? 0 : buffer.remaining();
            createHeader();
            if (buffer == null) {
                super.send(headerArray, handler);
            } else {
                ByteBuffer headbuf = channel.pollWriteBuffer();
                headbuf.put(headerArray.content(), 0, headerArray.length());
                headbuf.flip();
                super.send(new ByteBuffer[]{headbuf, buffer}, null, handler);
            }
        } else {
            super.send(buffer, null, handler);
        }
    }

    /**
     * 将指定文件按响应结果输出
     *
     * @param file 输出文件
     *
     * @throws IOException IO异常
     */
    public void finish(File file) throws IOException {
        finishFile(null, file, null);
    }

    /**
     * 将文件按指定文件名输出
     *
     * @param filename 输出文件名
     * @param file     输出文件
     *
     * @throws IOException IO异常
     */
    public void finish(final String filename, File file) throws IOException {
        finishFile(filename, file, null);
    }

    /**
     * 将指定文件句柄或文件内容按响应结果输出，若fileBody不为null则只输出fileBody内容
     *
     * @param file     输出文件
     * @param fileBody 文件内容， 没有则输出file
     *
     * @throws IOException IO异常
     */
    protected void finishFile(final File file, ByteBuffer fileBody) throws IOException {
        finishFile(null, file, fileBody);
    }

    /**
     * 将指定文件句柄或文件内容按指定文件名输出，若fileBody不为null则只输出fileBody内容
     * file 与 fileBody 不能同时为空
     * file 与 filename 也不能同时为空
     *
     * @param filename 输出文件名
     * @param file     输出文件
     * @param fileBody 文件内容， 没有则输出file
     *
     * @throws IOException IO异常
     */
    protected void finishFile(final String filename, final File file, ByteBuffer fileBody) throws IOException {
        if ((file == null || !file.isFile() || !file.canRead()) && fileBody == null) {
            finish404();
            return;
        }
        if (fileBody != null) fileBody = fileBody.duplicate().asReadOnlyBuffer();
        final long length = file == null ? fileBody.remaining() : file.length();
        final String match = request.getHeader("If-None-Match");
        final String etag = (file == null ? 0L : file.lastModified()) + "-" + length;
        if (match != null && etag.equals(match)) {
            //finish304();
            //return;
        }
        this.contentLength = length;
        if (filename != null && !filename.isEmpty() && file != null) {
            if (this.header.getValue("Content-Disposition") == null) {
                addHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(filename, "UTF-8"));
            }
        }
        this.contentType = MimeType.getByFilename(filename == null || filename.isEmpty() ? file.getName() : filename);
        if (this.contentType == null) this.contentType = "application/octet-stream";
        String range = request.getHeader("Range");
        if (range != null && (!range.startsWith("bytes=") || range.indexOf(',') >= 0)) range = null;
        long start = -1;
        long len = -1;
        if (range != null) {
            range = range.substring("bytes=".length());
            int pos = range.indexOf('-');
            start = pos == 0 ? 0 : Integer.parseInt(range.substring(0, pos));
            long end = (pos == range.length() - 1) ? -1 : Long.parseLong(range.substring(pos + 1));
            long clen = end > 0 ? (end - start + 1) : (length - start);
            this.status = 206;
            addHeader("Accept-Ranges", "bytes");
            addHeader("Content-Range", "bytes " + start + "-" + (end > 0 ? end : length - 1) + "/" + length);
            this.contentLength = clen;
            len = end > 0 ? clen : end;
        }
        this.addHeader("ETag", etag);
        createHeader();
        ByteBuffer hbuffer = channel.pollWriteBuffer();
        hbuffer.put(headerArray.content(), 0, headerArray.length());
        hbuffer.flip();
        if (fileBody == null) {
            if (this.recycleListener != null) this.output = file;
            finishFile(hbuffer, file, start, len);
        } else {
            if (start >= 0) {
                fileBody.position((int) start);
                if (len > 0) fileBody.limit((int) (fileBody.position() + len));
            }
            if (this.recycleListener != null) this.output = fileBody;
            super.finish(hbuffer, fileBody);
        }
    }

    private void finishFile(ByteBuffer hbuffer, File file, long offset, long length) throws IOException {
        this.channel.write(hbuffer, hbuffer, new TransferFileHandler(file, offset, length));
    }

    /**
     * 跳过header的输出
     * 通常应用场景是，调用者的输出内容里已经包含了HTTP的响应头信息，因此需要调用此方法避免重复输出HTTP响应头信息。
     *
     * @return HttpResponse
     */
    public HttpResponse skipHeader() {
        this.headWritedSize = 0;
        return this;
    }

    protected DefaultAnyValue duplicateHeader() {
        return this.header.duplicate();
    }

    /**
     * 设置Header值
     *
     * @param name  header名
     * @param value header值
     *
     * @return HttpResponse
     */
    public HttpResponse setHeader(String name, Object value) {
        this.header.setValue(name, String.valueOf(value));
        if ("Connection".equalsIgnoreCase(name)) this.respHeadContainsConnection = true;
        return this;
    }

    /**
     * 添加Header值
     *
     * @param name  header名
     * @param value header值
     *
     * @return HttpResponse
     */
    public HttpResponse addHeader(String name, Object value) {
        this.header.addValue(name, String.valueOf(value));
        if ("Connection".equalsIgnoreCase(name)) this.respHeadContainsConnection = true;
        return this;
    }

    /**
     * 添加Header值
     *
     * @param map header值
     *
     * @return HttpResponse
     */
    public HttpResponse addHeader(Map<String, ?> map) {
        if (map == null || map.isEmpty()) return this;
        for (Map.Entry<String, ?> en : map.entrySet()) {
            this.header.addValue(en.getKey(), String.valueOf(en.getValue()));
            if (!respHeadContainsConnection && "Connection".equalsIgnoreCase(en.getKey())) {
                this.respHeadContainsConnection = true;
            }
        }
        return this;
    }

    /**
     * 设置状态码
     *
     * @param status 状态码
     *
     * @return HttpResponse
     */
    public HttpResponse setStatus(int status) {
        this.status = status;
        return this;
    }

    /**
     * 获取状态码
     *
     * @return 状态码
     */
    public int getStatus() {
        return this.status;
    }

    /**
     * 获取 ContentType
     *
     * @return ContentType
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * 设置 ContentType
     *
     * @param contentType ContentType
     *
     * @return HttpResponse
     */
    public HttpResponse setContentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    /**
     * 获取内容长度
     *
     * @return 内容长度
     */
    public long getContentLength() {
        return contentLength;
    }

    /**
     * 设置内容长度
     *
     * @param contentLength 内容长度
     *
     * @return HttpResponse
     */
    public HttpResponse setContentLength(long contentLength) {
        this.contentLength = contentLength;
        return this;
    }

    /**
     * 获取输出时的拦截器
     *
     * @return 拦截器
     */
    protected BiConsumer<HttpResponse, byte[]> getCacheHandler() {
        return cacheHandler;
    }

    /**
     * 设置输出时的拦截器
     *
     * @param cacheHandler 拦截器
     */
    protected void setCacheHandler(BiConsumer<HttpResponse, byte[]> cacheHandler) {
        this.cacheHandler = cacheHandler;
    }

    /**
     * 获取输出RetResult时的拦截器
     *
     * @return 拦截器
     */
    protected BiFunction<HttpRequest, org.redkale.service.RetResult, org.redkale.service.RetResult> getRetResultHandler() {
        return retResultHandler;
    }

    /**
     * 设置输出RetResult时的拦截器
     *
     * @param retResultHandler 拦截器
     */
    public void retResultHandler(BiFunction<HttpRequest, org.redkale.service.RetResult, org.redkale.service.RetResult> retResultHandler) {
        this.retResultHandler = retResultHandler;
    }

    protected final class TransferFileHandler implements CompletionHandler<Integer, ByteBuffer> {

        private final File file;

        private final AsynchronousFileChannel filechannel;

        private final long max; //需要读取的字节数， -1表示读到文件结尾

        private long count;//读取文件的字节数

        private long readpos = 0;

        private boolean hdwrite = true; //写入Header

        private boolean read = false;

        public TransferFileHandler(File file) throws IOException {
            this.file = file;
            this.filechannel = AsynchronousFileChannel.open(file.toPath(), options);
            this.readpos = 0;
            this.max = file.length();
        }

        public TransferFileHandler(File file, long offset, long len) throws IOException {
            this.file = file;
            this.filechannel = AsynchronousFileChannel.open(file.toPath(), options);
            this.readpos = offset <= 0 ? 0 : offset;
            this.max = len <= 0 ? file.length() : len;
        }

        @Override
        public void completed(Integer result, ByteBuffer attachment) {
            //(Utility.now() + "---" + Thread.currentThread().getName() + "-----------" + file + "-------------------result: " + result + ", max = " + max + ", readpos = " + readpos + ", count = " + count + ", " + (hdwrite ? "正在写Header" : (read ? "准备读" : "准备写")));
            if (result < 0 || count >= max) {
                failed(null, attachment);
                return;
            }
            if (hdwrite && attachment.hasRemaining()) { //Header还没写完
                channel.write(attachment, attachment, this);
                return;
            }
            if (hdwrite) {
                //(Utility.now() + "---" + Thread.currentThread().getName() + "-----------" + file + "-------------------Header写入完毕， 准备读取文件.");
                hdwrite = false;
                read = true;
                result = 0;
            }
            if (read) {
                count += result;
            } else {
                readpos += result;
            }
            if (read && attachment.hasRemaining()) { //Buffer还没写完
                channel.write(attachment, attachment, this);
                return;
            }

            if (read) {
                read = false;
                attachment.clear();
                filechannel.read(attachment, readpos, attachment, this);
            } else {
                read = true;
                if (count > max) {
                    attachment.limit((int) (attachment.position() + max - count));
                }
                attachment.flip();
                if (attachment.hasRemaining()) {
                    channel.write(attachment, attachment, this);
                } else {
                    failed(null, attachment);
                }
            }
        }

        @Override
        public void failed(Throwable exc, ByteBuffer attachment) {
            channel.offerBuffer(attachment);
            finish(true);
            try {
                filechannel.close();
            } catch (IOException e) {
            }
        }

    }

    public static class HttpResponseConfig {

        public String plainContentType;

        public String jsonContentType;

        public byte[] plainContentTypeBytes;

        public byte[] jsonContentTypeBytes;

        public String[][] defaultAddHeaders;

        public String[][] defaultSetHeaders;

        public HttpCookie defaultCookie;

        public boolean autoOptions;

        public Supplier<byte[]> dateSupplier;

        public List< HttpRender> renders;

        public final Map<Integer, byte[]> plainLiveContentLengthMap = new HashMap<>();

        public final Map<Integer, byte[]> jsonLiveContentLengthMap = new HashMap<>();

        public final Map<Integer, byte[]> plainCloseContentLengthMap = new HashMap<>();

        public final Map<Integer, byte[]> jsonCloseContentLengthMap = new HashMap<>();

        public HttpResponseConfig init(AnyValue config) {
            if (this.plainContentTypeBytes == null) {
                String plainct = plainContentType == null || plainContentType.isEmpty() ? "text/plain; charset=utf-8" : plainContentType;
                String jsonct = jsonContentType == null || jsonContentType.isEmpty() ? "application/json; charset=utf-8" : jsonContentType;
                this.plainContentType = plainct;
                this.jsonContentType = jsonct;
                this.plainContentTypeBytes = ("Content-Type: " + plainct + "\r\n").getBytes();
                this.jsonContentTypeBytes = ("Content-Type: " + jsonct + "\r\n").getBytes();
                for (int i = 0; i <= cacheMaxContentLength; i++) {
                    byte[] lenbytes = ("Content-Length: " + i + "\r\n").getBytes();
                    plainLiveContentLengthMap.put(i, append(append(status200_server_live_Bytes, plainContentTypeBytes), lenbytes));
                    plainCloseContentLengthMap.put(i, append(append(status200_server_close_Bytes, plainContentTypeBytes), lenbytes));
                    jsonLiveContentLengthMap.put(i, append(append(status200_server_live_Bytes, jsonContentTypeBytes), lenbytes));
                    jsonCloseContentLengthMap.put(i, append(append(status200_server_close_Bytes, jsonContentTypeBytes), lenbytes));
                }
            }
            return this;
        }

        @Override
        public String toString() {
            return JsonConvert.root().convertTo(this);
        }
    }
}
