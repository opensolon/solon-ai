---
title: "harness - 内置拦截器的修改及添加"
---

solon-ai-harness 已经预期了：

* compressionInterceptor ，负责上下文压缩处理
* hitlInterceptor，处理人工介入处理


具体参考：[《react - ReActInterceptor 拦截器》](/article/1316)


### 1、修改现有的内置的拦截器

下面示意一下，具体需要按需定制（一般不需要）

```java
HarnessEngine engine = HarnessEngine.of(...)
                .sessionProvider(sessionProvider)
                .compressionInterceptor(new ContextCompressionInterceptor())
                .hitlInterceptor(new HITLInterceptor())
                .build();
```


### 2、添加新的拦截器

通过 extensionAdd 方式，进一步定制智能体。其中包括添加“拦截器”（或者工具等）

```java
HarnessEngine engine = HarnessEngine.of(...)
                .sessionProvider(sessionProvider)
                .extensionAdd((agentName,agentBuilder)->{
                    agentBuilder.defaultInterceptorAdd(new ReActInterceptor() {
                        @Override
                        public void onAgentStart(ReActTrace trace) {
                            ReActInterceptor.super.onAgentStart(trace);
                        }
                    });
                })
                .build();
```
