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
package org.noear.solon.ai.skills.memory.store;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.StringCodec;
import org.noear.solon.ai.skills.memory.MemoryStoreProvider;

import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author noear 2026/3/4 created
 *
 */
public class MemoryStoreProviderRogueImpl implements MemoryStoreProvider {
    private final RogueMap<String, String> rogueMap;

    public MemoryStoreProviderRogueImpl(String filePath) {
        this.rogueMap = RogueMap.<String, String>mmap()
                .persistent(filePath)
                .autoExpand(true)
                .allocateSize(64 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build();
    }

    public MemoryStoreProviderRogueImpl(RogueMap<String, String> rogueMap) {
        this.rogueMap = rogueMap;
    }

    @Override
    public void put(String key, String val, int ttl) {
        rogueMap.put(key, val, ttl, TimeUnit.SECONDS);
        rogueMap.checkpoint();
    }

    @Override
    public String get(String key) {
        return rogueMap.get(key);
    }

    @Override
    public void remove(String key) {
        rogueMap.remove(key);
        rogueMap.checkpoint();
    }
}