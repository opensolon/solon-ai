/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.memory.store;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.StringCodec;
import org.noear.solon.ai.talents.memory.MemoryStorer;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author noear 2026/3/4 created
 *
 */
public class MemoryStorerRogueImpl implements MemoryStorer {
    private final RogueMap<String, String> rogueMap;
    private final List<String> scopeOrder;

    public MemoryStorerRogueImpl(String filePath) {
        this.rogueMap = RogueMap.<String, String>mmap()
                .persistent(filePath)
                .autoExpand(true)
                .allocateSize(64 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build();
        this.scopeOrder = Arrays.asList(MemorySolutionProvider.SCOPE_USER);
    }

    public MemoryStorerRogueImpl(String filePath, List<String> scopeOrder) {
        this.rogueMap = RogueMap.<String, String>mmap()
                .persistent(filePath)
                .autoExpand(true)
                .allocateSize(64 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build();
        this.scopeOrder = scopeOrder;
    }

    public MemoryStorerRogueImpl(RogueMap<String, String> rogueMap) {
        this.rogueMap = rogueMap;
        this.scopeOrder = Arrays.asList(MemorySolutionProvider.SCOPE_USER);
    }

    public MemoryStorerRogueImpl(RogueMap<String, String> rogueMap,  List<String> scopeOrder) {
        this.rogueMap = rogueMap;
        this.scopeOrder = scopeOrder;
    }

    private String getFinalKey(String bucketKey, String key, String scope) {
        return (scope != null && !scope.isEmpty() ? scope + ":" : "") + bucketKey + ":" + key;
    }

    @Override
    public void put(String userId, String key, String val, int ttl, String scope) {
        if (ttl < 0) {
            // ttl=-1 表示永久存储，不设置过期时间
            rogueMap.put(getFinalKey(userId, key, scope), val);
        } else {
            rogueMap.put(getFinalKey(userId, key, scope), val, ttl, TimeUnit.SECONDS);
        }
        rogueMap.checkpoint();
    }

    @Override
    public String get(String userId, String key) {
        String result = null;
        for (String scope : scopeOrder) {
            String val = rogueMap.get(getFinalKey(userId, key, scope));
            if (val != null) {
                result = val; // 后遍历的 scope 覆盖先遍历的（workspace 优先级最高）
            }
        }
        return result;
    }

    @Override
    public void remove(String userId, String key) {
        for (String scope : scopeOrder) {
            rogueMap.remove(getFinalKey(userId, key, scope));
        }
        rogueMap.checkpoint();
    }
}