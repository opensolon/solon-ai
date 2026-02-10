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
package org.noear.solon.ai.mcp.primitives.prompt;

import org.noear.eggg.MethodEggg;
import org.noear.eggg.ParamEggg;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.PromptMapping;
import org.noear.solon.ai.chat.tool.MethodExecuteHandler;
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;
import org.noear.solon.ai.util.ParamDesc;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.wrap.MethodWrap;
import org.noear.solon.rx.SimpleSubscriber;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 方法构建的函数提示语
 *
 * @author noear
 * @since 3.2
 */
public class MethodFunctionPrompt implements FunctionPrompt {
    static final Logger log = LoggerFactory.getLogger(MethodFunctionPrompt.class);

    private final BeanWrap beanWrap;
    private final MethodWrap methodWrap;

    private final String name;
    private final PromptMapping mapping;
    private final Map<String, Object> meta = new HashMap<>();

    private final Map<String, ParamDesc> params;

    public MethodFunctionPrompt(BeanWrap beanWrap, MethodEggg methodEggg) {
        this.beanWrap = beanWrap;
        this.methodWrap = new MethodWrap(beanWrap.context(), beanWrap.clz(), methodEggg);
        this.mapping = methodEggg.getMethod().getAnnotation(PromptMapping.class);
        this.name = Utils.annoAlias(mapping.name(), methodEggg.getName());

        //断言
        Assert.notNull(mapping, "@PromptMapping annotation is missing");

        //断言
        //Assert.notEmpty(mapping.description(), "PromptMapping description cannot be empty");

        //检查返回类型
        if (Collection.class.isAssignableFrom(methodEggg.getReturnTypeEggg().getType()) == false) {
            throw new IllegalArgumentException("@PromptMapping return type is not Collection");
        }

        if (Assert.isNotEmpty(mapping.meta()) && mapping.meta().length() > 3) {
            Map<String, Object> tmp = ONode.deserialize(mapping.meta(), Map.class);
            meta.putAll(tmp);
        }

        this.params = new LinkedHashMap<>();

        for (ParamEggg p1 : methodEggg.getParamEgggAry()) {
            Map<String, ParamDesc> paramMap = ToolSchemaUtil.buildInputParams(p1.getParam(), p1.getTypeEggg());
            if (Utils.isNotEmpty(paramMap)) {
                params.putAll(paramMap);
            }
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String title() {
        return mapping.title();
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
    public Collection<ParamDesc> params() {
        return params.values();
    }

    @Override
    public Object handle(Map<String, Object> args) throws Throwable {
        return handleAsync(args).get();
    }

    @Override
    public CompletableFuture<Object> handleAsync(Map<String, Object> args) {
        CompletableFuture<Object> returnFuture = new CompletableFuture<>();

        try {
            Object handleR = doHandle(args);

            if (handleR instanceof CompletableFuture) {
                CompletableFuture<Object> handleF = (CompletableFuture<Object>) handleR;
                handleF.whenComplete((rst1, err) -> {
                    if (err != null) {
                        if (log.isWarnEnabled()) {
                            log.warn("Prompt handle error, name: '{}'", name, err);
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
                                log.warn("Prompt handle error, name: '{}'", name, err);
                            }
                            returnFuture.completeExceptionally(err);
                        }));
            } else {
                doConvert(handleR, returnFuture);
            }

        } catch (Throwable ex) {
            if (log.isWarnEnabled()) {
                log.warn("Prompt handle error, name: '{}'", name, ex);
            }
            returnFuture.completeExceptionally(ex);
        }

        return returnFuture;
    }

    private void doConvert(Object rst, CompletableFuture<Object> returnFuture) {
        returnFuture.complete(rst);
    }

    private Object doHandle(Map<String, Object> args) throws Throwable {
        Context ctx = Context.current();
        if (ctx == null) {
            ctx = new ContextEmpty();
        }

        ctx.attrSet(MethodExecuteHandler.MCP_BODY_ATTR, args);

        ctx.result = MethodExecuteHandler.getInstance()
                .executeHandle(ctx, beanWrap.get(), methodWrap);

        return ctx.result;
    }
}