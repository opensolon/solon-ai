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
package org.noear.solon.ai.chat.tool;

import org.noear.eggg.MethodEggg;
import org.noear.eggg.ParamEggg;
import org.noear.snack4.ONode;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.util.ParamDesc;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.ClassUtil;
import org.noear.solon.core.wrap.MethodWrap;
import org.noear.solon.rx.SimpleSubscriber;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 方法构建的函数工具
 *
 * @author noear
 * @since 3.1
 */
public class MethodFunctionTool implements FunctionTool {
    static final Logger log = LoggerFactory.getLogger(MethodFunctionTool.class);

    private final BeanWrap beanWrap;
    private final MethodWrap methodWrap;
    private final Type returnType;

    private final String name;
    private final String title;
    private final String description;
    private final Map<String, Object> meta = new HashMap<>();

    private final boolean returnDirect;
    private final Map<String, ParamDesc> params;
    private final ToolCallResultConverter resultConverter;
    private final String inputSchema;
    private String outputSchema;


    public MethodFunctionTool(BeanWrap beanWrap, MethodEggg methodEggg) {
        this.beanWrap = beanWrap;
        this.methodWrap = new MethodWrap(beanWrap.context(), beanWrap.clz(), methodEggg);
        this.returnType = methodEggg.getGenericReturnType();

        ToolMapping mapping = methodEggg.getMethod().getAnnotation(ToolMapping.class);

        //断言
        Assert.notNull(mapping, "@ToolMapping annotation is missing");
        //断言
        //Assert.notEmpty(mapping.description(), "ToolMapping description cannot be empty");

        this.name = Utils.annoAlias(mapping.name(), methodEggg.getName());
        this.title = mapping.title();
        this.description = mapping.description();
        this.returnDirect = mapping.returnDirect();
        this.params = new LinkedHashMap<>();

        if(Assert.isNotEmpty(mapping.meta()) && mapping.meta().length() > 3) {
            Map<String, Object> tmp = ONode.deserialize(mapping.meta(), Map.class);
            meta.putAll(tmp);
        }

        if (mapping.resultConverter() == ToolCallResultConverter.class
                || mapping.resultConverter() == ToolCallResultConverterDefault.class) {
            resultConverter = ToolCallResultConverterDefault.getInstance();
        } else {
            if (Solon.app() != null) {
                resultConverter = Solon.context().getBeanOrNew(mapping.resultConverter());
            } else {
                resultConverter = ClassUtil.newInstance(mapping.resultConverter());
            }
        }

        for (ParamEggg p1 : methodEggg.getParamEgggAry()) {
            Map<String, ParamDesc> paramMap = ToolSchemaUtil.buildInputParams(p1.getParam(), p1.getTypeEggg());
            if (Utils.isNotEmpty(paramMap)) {
                params.putAll(paramMap);
            }
        }

        inputSchema = ToolSchemaUtil.buildInputSchema(params.values());

        // 输出参数 outputSchema
        {

            if (ToolSchemaUtil.isIgnoreOutputSchema(returnType) == false) {
                outputSchema = ToolSchemaUtil.buildOutputSchema(returnType);
            } else {
                outputSchema = "";
            }
        }
    }


    /**
     * 名字
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * 标题
     */
    @Override
    public String title() {
        return title;
    }

    /**
     * 描述
     */
    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> meta() {
        return meta;
    }

    @Override
    public void metaPut(String key, Object value) {
        meta.put(key,value);
    }

    @Override
    public boolean returnDirect() {
        return returnDirect;
    }

    /**
     * 输入架构
     */
    @Override
    public String inputSchema() {
        return inputSchema;
    }

    @Override
    public String outputSchema() {
        return outputSchema;
    }


    @Override
    public Type returnType() {
        return returnType;
    }

    @Override
    public ToolCallResultConverter resultConverter() {
        return resultConverter;
    }

    /**
     * 执行处理
     */
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
                            log.warn("Tool handle error, name: '{}'", name, err);
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
                                log.warn("Tool handle error, name: '{}'", name, err);
                            }
                            returnFuture.completeExceptionally(err);
                        }));
            } else {
                returnFuture.complete(handleR);
                //doConvert(handleR, returnFuture);
            }
        } catch (Throwable ex) {
            if (log.isWarnEnabled()) {
                log.warn("Tool handle error, name: '{}'", name, ex);
            }
            returnFuture.completeExceptionally(ex);
        }

        return returnFuture;
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

    @Override
    public String toString() {
        return "MethodFunctionTool{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", returnDirect=" + returnDirect +
                ", inputSchema=" + inputSchema() +
                ", outputSchema=" + outputSchema() +
                '}';
    }
}