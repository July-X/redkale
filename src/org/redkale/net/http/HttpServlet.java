/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net.http;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import org.redkale.asm.*;
import static org.redkale.asm.ClassWriter.COMPUTE_FRAMES;
import static org.redkale.asm.Opcodes.*;
import org.redkale.net.*;
import org.redkale.service.RetResult;
import org.redkale.util.*;

/**
 * HTTP版的Servlet， 执行顺序 execute --&#62; preExecute --&#62; authenticate --&#62; HttpMapping对应的方法
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
public class HttpServlet extends Servlet<HttpContext, HttpRequest, HttpResponse> {

    public static final int RET_SERVER_ERROR = 1200_0001;

    public static final int RET_METHOD_ERROR = 1200_0002;

    String _prefix = ""; //当前HttpServlet的path前缀

    HashMap<String, ActionEntry> _actionmap;  //Rest生成时赋值, 字段名Rest有用到

    private Map.Entry<String, ActionEntry>[] mappings; //字段名Rest有用到

    //这里不能直接使用HttpServlet，会造成死循环初始化HttpServlet
    private final Servlet<HttpContext, HttpRequest, HttpResponse> authSuccessServlet = new Servlet<HttpContext, HttpRequest, HttpResponse>() {
        @Override
        public void execute(HttpRequest request, HttpResponse response) throws IOException {
            ActionEntry entry = request.actionEntry;
            if (entry.rpconly && !request.rpc) {
                response.finish(503, null);
                return;
            }
            if (entry.cacheseconds > 0) {//有缓存设置
                CacheEntry ce = entry.modeOneCache ? entry.oneCache : entry.cache.get(request.getRequestURI());
                if (ce != null && ce.time + entry.cacheseconds * 1000 > System.currentTimeMillis()) { //缓存有效
                    response.setStatus(ce.status);
                    response.setContentType(ce.contentType);
                    response.skipHeader();
                    response.finish(ce.getBytes());
                    return;
                }
                response.setCacheHandler(entry.cacheHandler);
            }
            entry.servlet.execute(request, response);
        }
    };

    //preExecute运行完后执行的Servlet
    private final Servlet<HttpContext, HttpRequest, HttpResponse> preSuccessServlet = new Servlet<HttpContext, HttpRequest, HttpResponse>() {
        @Override
        public void execute(HttpRequest request, HttpResponse response) throws IOException {
            if (request.actionEntry != null) {
                ActionEntry entry = request.actionEntry;
                if (!entry.checkMethod(request.getMethod())) {
                    response.finishJson(new RetResult(RET_METHOD_ERROR, "Method(" + request.getMethod() + ") Error"));
                    return;
                }
                request.moduleid = entry.moduleid;
                request.actionid = entry.actionid;
                request.annotations = entry.annotations;
                if (entry.auth) {
                    response.thenEvent(authSuccessServlet);
                    authenticate(request, response);
                } else {
                    authSuccessServlet.execute(request, response);
                }
                return;
            }
            for (Map.Entry<String, ActionEntry> en : mappings) {
                if (request.getRequestURI().startsWith(en.getKey())) {
                    ActionEntry entry = en.getValue();
                    if (!entry.checkMethod(request.getMethod())) {
                        response.finishJson(new RetResult(RET_METHOD_ERROR, "Method(" + request.getMethod() + ") Error"));
                        return;
                    }
                    request.actionEntry = entry;
                    request.moduleid = entry.moduleid;
                    request.actionid = entry.actionid;
                    request.annotations = entry.annotations;
                    if (entry.auth) {
                        response.thenEvent(authSuccessServlet);
                        authenticate(request, response);
                    } else {
                        authSuccessServlet.execute(request, response);
                    }
                    return;
                }
            }
            response.finish404();
            //throw new IOException(this.getClass().getName() + " not found method for URI(" + request.getRequestURI() + ")");
        }
    };

    @SuppressWarnings("unchecked")
    void preInit(HttpContext context, AnyValue config) {
        if (this.mappings != null) return; //无需重复preInit
        String path = _prefix == null ? "" : _prefix;
        WebServlet ws = this.getClass().getAnnotation(WebServlet.class);
        if (ws != null && !ws.repair()) path = "";
        HashMap<String, ActionEntry> map = this._actionmap != null ? this._actionmap : loadActionEntry();
        this.mappings = new Map.Entry[map.size()];
        int i = -1;
        for (Map.Entry<String, ActionEntry> en : map.entrySet()) {
            mappings[++i] = new AbstractMap.SimpleEntry<>(path + en.getKey(), en.getValue());
        }
        //必须要倒排序, /query /query1 /query12  确保含子集的优先匹配 /query12  /query1  /query
        Arrays.sort(mappings, (o1, o2) -> o2.getKey().compareTo(o1.getKey()));
    }

    void postDestroy(HttpContext context, AnyValue config) {
    }

    //Server执行start后运行此方法
    public void postStart(HttpContext context, AnyValue config) {
    }

    /**
     * <p>
     * 预执行方法，在execute方法之前运行，设置当前用户信息，或者加入常规统计和基础检测，例如 : <br>
     * <blockquote><pre>
     *      &#64;Override
     *      public void preExecute(final HttpRequest request, final HttpResponse response) throws IOException {
     *          //设置当前用户信息
     *          final String sessionid = request.getSessionid(false);
     *          if (sessionid != null) request.setCurrentUserid(userService.currentUserid(sessionid));
     *
     *          if (finer) response.recycleListener((req, resp) -&#62; {  //记录处理时间比较长的请求
     *              long e = System.currentTimeMillis() - ((HttpRequest) req).getCreatetime();
     *              if (e &#62; 200) logger.finer("http-execute-cost-time: " + e + " ms. request = " + req);
     *          });
     *          response.nextEvent();
     *      }
     * </pre></blockquote>
     * <p>
     *
     * @param request  HttpRequest
     * @param response HttpResponse
     *
     * @throws IOException IOException
     */
    protected void preExecute(HttpRequest request, HttpResponse response) throws IOException {
        response.nextEvent();
    }

    /**
     * <p>
     * 用户登录或权限验证， 注解为&#64;HttpMapping.auth == true 的方法会执行authenticate方法, 若验证成功则必须调用response.nextEvent();进行下一步操作, 例如: <br>
     * <blockquote><pre>
     *      &#64;Override
     *      public void authenticate(HttpRequest request, HttpResponse response) throws IOException {
     *          Serializable userid = request.currentUserid();
     *          if (userid == null) {
     *              response.finishJson(RET_UNLOGIN);
     *              return;
     *          }
     *          response.nextEvent();
     *      }
     * </pre></blockquote>
     * <p>
     *
     *
     * @param request  HttpRequest
     * @param response HttpResponse
     *
     * @throws IOException IOException
     */
    protected void authenticate(HttpRequest request, HttpResponse response) throws IOException {
        response.nextEvent();
    }

    @Override
    public void execute(HttpRequest request, HttpResponse response) throws IOException {
        response.thenEvent(preSuccessServlet);
        preExecute(request, response);
    }

    private HashMap<String, ActionEntry> loadActionEntry() {
        WebServlet module = this.getClass().getAnnotation(WebServlet.class);
        final int serviceid = module == null ? 0 : module.moduleid();
        final HashMap<String, ActionEntry> map = new HashMap<>();
        HashMap<String, Class> nameset = new HashMap<>();
        final Class selfClz = this.getClass();
        Class clz = this.getClass();
        do {
            if (java.lang.reflect.Modifier.isAbstract(clz.getModifiers())) break;
            for (final Method method : clz.getMethods()) {
                //-----------------------------------------------
                String methodname = method.getName();
                if ("service".equals(methodname) || "preExecute".equals(methodname) || "execute".equals(methodname) || "authenticate".equals(methodname)) continue;
                //-----------------------------------------------
                Class[] paramTypes = method.getParameterTypes();
                if (paramTypes.length != 2 || paramTypes[0] != HttpRequest.class || paramTypes[1] != HttpResponse.class) continue;
                //-----------------------------------------------
                Class[] exps = method.getExceptionTypes();
                if (exps.length > 0 && (exps.length != 1 || exps[0] != IOException.class)) continue;
                //-----------------------------------------------

                final HttpMapping mapping = method.getAnnotation(HttpMapping.class);
                if (mapping == null) continue;
                final boolean inherited = mapping.inherited();
                if (!inherited && selfClz != clz) continue; //忽略不被继承的方法
                final int actionid = mapping.actionid();
                final String name = mapping.url().trim();
                final String[] methods = mapping.methods();
                if (nameset.containsKey(name)) {
                    if (nameset.get(name) != clz) continue;
                    throw new RuntimeException(this.getClass().getSimpleName() + " have two same " + HttpMapping.class.getSimpleName() + "(" + name + ")");
                }
                nameset.put(name, clz);
                map.put(name, new ActionEntry(serviceid, actionid, name, methods, method, createActionServlet(method)));
            }
        } while ((clz = clz.getSuperclass()) != HttpServlet.class);
        return map;
    }

    protected static final class ActionEntry {

        ActionEntry(int moduleid, int actionid, String name, String[] methods, Method method, HttpServlet servlet) {
            this(moduleid, actionid, name, methods, method, rpconly(method), auth(method), cacheseconds(method), servlet);
            this.annotations = annotations(method);
        }

        //供Rest类使用，参数不能随便更改
        public ActionEntry(int moduleid, int actionid, String name, String[] methods, Method method, boolean rpconly, boolean auth, int cacheseconds, HttpServlet servlet) {
            this.moduleid = moduleid;
            this.actionid = actionid;
            this.name = name;
            this.methods = methods;
            this.method = method;  //rest构建会为null
            this.servlet = servlet;
            this.rpconly = rpconly;
            this.auth = auth;
            this.cacheseconds = cacheseconds;
            if (Utility.contains(name, '.', '*', '{', '[', '(', '|', '^', '$', '+', '?', '\\') || name.endsWith("/")) { //是否是正则表达式
                this.modeOneCache = false;
                this.cache = cacheseconds > 0 ? new ConcurrentHashMap<>() : null;
                this.cacheHandler = cacheseconds > 0 ? (HttpResponse response, byte[] content) -> {
                    int status = response.getStatus();
                    if (status != 200) return;
                    CacheEntry ce = new CacheEntry(response.getStatus(), response.getContentType(), content);
                    cache.put(response.getRequest().getRequestURI(), ce);
                } : null;
            } else { //单一url
                this.modeOneCache = true;
                this.cache = null;
                this.cacheHandler = cacheseconds > 0 ? (HttpResponse response, byte[] content) -> {
                    int status = response.getStatus();
                    if (status != 200) return;
                    oneCache = new CacheEntry(response.getStatus(), response.getContentType(), content);
                } : null;
            }
        }

        protected static boolean auth(Method method) {
            HttpMapping mapping = method.getAnnotation(HttpMapping.class);
            return mapping == null || mapping.auth();
        }

        protected static boolean rpconly(Method method) {
            HttpMapping mapping = method.getAnnotation(HttpMapping.class);
            return mapping == null || mapping.rpconly();
        }

        protected static int cacheseconds(Method method) {
            HttpMapping mapping = method.getAnnotation(HttpMapping.class);
            return mapping == null ? 0 : mapping.cacheseconds();
        }

        //Rest.class会用到此方法
        protected static Annotation[] annotations(Method method) {
            return method.getAnnotations();
        }

        boolean isNeedCheck() {
            return this.moduleid != 0 || this.actionid != 0;
        }

        boolean checkMethod(final String reqMethod) {
            if (methods.length == 0) return true;
            for (String m : methods) {
                if (reqMethod.equalsIgnoreCase(m)) return true;
            }
            return false;
        }

        final BiConsumer<HttpResponse, byte[]> cacheHandler;

        final ConcurrentHashMap<String, CacheEntry> cache;

        final boolean modeOneCache;

        final int cacheseconds;

        final boolean rpconly;

        final boolean auth;

        final int moduleid;

        final int actionid;

        final String name;

        final String[] methods;

        final HttpServlet servlet;

        Method method;

        CacheEntry oneCache;

        Annotation[] annotations;
    }

    private HttpServlet createActionServlet(final Method method) {
        //------------------------------------------------------------------------------
        final String supDynName = HttpServlet.class.getName().replace('.', '/');
        final String interName = this.getClass().getName().replace('.', '/');
        final String interDesc = org.redkale.asm.Type.getDescriptor(this.getClass());
        final String requestSupDesc = org.redkale.asm.Type.getDescriptor(Request.class);
        final String responseSupDesc = org.redkale.asm.Type.getDescriptor(Response.class);
        final String requestDesc = org.redkale.asm.Type.getDescriptor(HttpRequest.class);
        final String responseDesc = org.redkale.asm.Type.getDescriptor(HttpResponse.class);
        String newDynName = interName + "_Dyn_" + method.getName();
        int i = 0;
        for (;;) {
            try {
                Thread.currentThread().getContextClassLoader().loadClass(newDynName.replace('/', '.'));
                newDynName += "_" + (++i);
            } catch (Throwable ex) {
                break;
            }
        }
        //------------------------------------------------------------------------------
        ClassWriter cw = new ClassWriter(COMPUTE_FRAMES);
        FieldVisitor fv;
        MethodVisitor mv;
        AnnotationVisitor av0;
        final String factfield = "_factServlet";
        cw.visit(V1_8, ACC_PUBLIC + ACC_FINAL + ACC_SUPER, newDynName, null, supDynName, null);
        {
            fv = cw.visitField(ACC_PUBLIC, factfield, interDesc, null, null);
            fv.visitEnd();
        }
        { //构造函数
            mv = (cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null));
            //mv.setDebug(true);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, supDynName, "<init>", "()V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        {
            mv = (cw.visitMethod(ACC_PUBLIC, "execute", "(" + requestDesc + responseDesc + ")V", null, new String[]{"java/io/IOException"}));
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, newDynName, factfield, interDesc);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, interName, method.getName(), "(" + requestDesc + responseDesc + ")V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(3, 3);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "execute", "(" + requestSupDesc + responseSupDesc + ")V", null, new String[]{"java/io/IOException"});
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitTypeInsn(CHECKCAST, HttpRequest.class.getName().replace('.', '/'));
            mv.visitVarInsn(ALOAD, 2);
            mv.visitTypeInsn(CHECKCAST, HttpResponse.class.getName().replace('.', '/'));
            mv.visitMethodInsn(INVOKEVIRTUAL, newDynName, "execute", "(" + requestDesc + responseDesc + ")V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(3, 3);
            mv.visitEnd();
        }
        cw.visitEnd();
        //------------------------------------------------------------------------------
        byte[] bytes = cw.toByteArray();
        Class<?> newClazz = new ClassLoader(this.getClass().getClassLoader()) {
            public final Class<?> loadClass(String name, byte[] b) {
                return defineClass(name, b, 0, b.length);
            }
        }.loadClass(newDynName.replace('/', '.'), bytes);
        try {
            HttpServlet instance = (HttpServlet) newClazz.getDeclaredConstructor().newInstance();
            instance.getClass().getField(factfield).set(instance, this);
            return instance;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static final class CacheEntry {

        public final long time = System.currentTimeMillis();

        private final byte[] cacheBytes;

        private final int status;

        private final String contentType;

        public CacheEntry(int status, String contentType, byte[] cacheBytes) {
            this.status = status;
            this.contentType = contentType;
            this.cacheBytes = cacheBytes;
        }

        public byte[] getBytes() {
            return cacheBytes;
        }
    }

    static class HttpActionServlet extends HttpServlet {

        final ActionEntry action;

        final HttpServlet servlet;

        public HttpActionServlet(ActionEntry actionEntry, HttpServlet servlet) {
            this.action = actionEntry;
            this.servlet = servlet;
        }

        @Override
        public void execute(HttpRequest request, HttpResponse response) throws IOException {
            request.actionEntry = action;
            servlet.execute(request, response);
        }
    }
}
