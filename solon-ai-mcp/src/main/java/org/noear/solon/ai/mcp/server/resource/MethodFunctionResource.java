/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.mcp.server.resource;

import org.noear.eggg.MethodEggg;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ResourceMapping;
import org.noear.solon.ai.chat.tool.MethodExecuteHandler;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.annotation.Produces;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.PathMatcher;
import org.noear.solon.core.util.PathUtil;
import org.noear.solon.core.wrap.MethodWrap;
import org.noear.solon.rx.SimpleSubscriber;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;

/**
 * 方法构建的函数资源
 *
 * @author noear
 * @since 3.2
 */
public class MethodFunctionResource implements FunctionResource {
    static final Logger log = LoggerFactory.getLogger(MethodFunctionResource.class);

    private final BeanWrap beanWrap;
    private final MethodWrap methodWrap;

    private final String name;
    private final String title;
    private final ResourceMapping mapping;
    private final Map<String, Object> meta = new HashMap<>();

    private final String mimeType;

    //path 分析器
    private PathMatcher pathKeysMatcher;//路径分析器
    //path key 列表
    private List<String> pathKeys;

    public MethodFunctionResource(BeanWrap beanWrap, MethodEggg methodEggg) {
        this.beanWrap = beanWrap;
        this.methodWrap = new MethodWrap(beanWrap.context(), beanWrap.clz(), methodEggg);

        this.mapping = methodEggg.getMethod().getAnnotation(ResourceMapping.class);
        this.name = Utils.annoAlias(mapping.name(), methodEggg.getName());


        //断言
        Assert.notNull(mapping, "@ResourceMapping annotation is missing");

        //断言
        //Assert.notEmpty(mapping.description(), "ResourceMapping description cannot be empty");

        if(Assert.isNotEmpty(mapping.meta()) && mapping.meta().length() > 3) {
            Map<String, Object> tmp = ONode.deserialize(mapping.meta(), Map.class);
            meta.putAll(tmp);
        }

        this.title = mapping.title();

        Produces producesAnno = methodEggg.getMethod().getAnnotation(Produces.class);
        if (producesAnno != null) {
            this.mimeType = producesAnno.value();
        } else {
            this.mimeType = mapping.mimeType();
        }

        //支持path变量
        if (mapping.uri() != null && mapping.uri().indexOf('{') >= 0) {
            pathKeys = new ArrayList<>();
            Matcher pm = PathUtil.pathKeyExpr.matcher(mapping.uri());
            while (pm.find()) {
                pathKeys.add(pm.group(1));
            }

            if (pathKeys.size() > 0) {
                pathKeysMatcher = PathMatcher.get(mapping.uri(), false);
            }
        }
    }

    @Override
    public String uri() {
        return mapping.uri();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String description() {
        return mapping.description();
    }

    @Override
    public Map<String, Object> meta() {
        return meta;
    }

    @Override
    public void metaPut(String key, Object value) {
        meta.put(key, value);
    }

    @Override
    public String mimeType() {
        return mimeType;
    }

    @Override
    public Object handle(String reqUri) throws Throwable {
        return handleAsync(reqUri).get();
    }

    @Override
    public CompletableFuture<Object> handleAsync(String reqUri) {
        CompletableFuture<Object> returnFuture = new CompletableFuture<>();

        try {
            Object handleR = doHandle(reqUri);

            if (handleR instanceof CompletableFuture) {
                CompletableFuture<Object> handleF = (CompletableFuture<Object>) handleR;
                handleF.whenComplete((rst1, err) -> {
                    if (err != null) {
                        if (log.isWarnEnabled()) {
                            log.warn("Resource handle error, name: '{}'", name, err);
                        }
                        returnFuture.completeExceptionally(err);
                    } else {
                        returnFuture.complete(rst1);
                        //doConvert(rst1, returnFuture);
                    }
                });
            } else if (handleR instanceof Publisher) {
                Publisher<Object> handleM = (Publisher) handleR;
                handleM.subscribe(new SimpleSubscriber<>()
                        .doOnSubscribe(subs -> {
                            subs.request(1);
                        })
                        .doOnNext(rst1 -> {
                            returnFuture.complete(rst1);
                            //doConvert(rst1, returnFuture);
                        }).doOnError(err -> {
                            if (log.isWarnEnabled()) {
                                log.warn("Resource handle error, name: '{}'", name, err);
                            }
                            returnFuture.completeExceptionally(err);
                        }));
            } else {
                returnFuture.complete(handleR);
                //doConvert(handleR, returnFuture);
            }

        } catch (Throwable ex) {
            if (log.isWarnEnabled()) {
                log.warn("Resource handle error, name: '{}'", name, ex);
            }
            returnFuture.completeExceptionally(ex);
        }

        return returnFuture;
    }

    private Object doHandle(String reqUri) throws Throwable {
        Context ctx = Context.current();
        if (ctx == null) {
            ctx = new ContextEmpty();
        }

        bindPathVarDo(ctx, reqUri);

        ctx.result = MethodExecuteHandler.getInstance()
                .executeHandle(ctx, beanWrap.get(), methodWrap);

        return ctx.result;
    }

    private void bindPathVarDo(Context c, String reqUri) throws Throwable {
        if (pathKeysMatcher != null) {
            Matcher pm = pathKeysMatcher.matcher(reqUri);
            if (pm.find()) {
                for (int i = 0, len = pathKeys.size(); i < len; i++) {
                    c.paramMap().add(pathKeys.get(i), pm.group(i + 1));//不采用group name,可解决_的问题
                }
            }
        }
    }
}