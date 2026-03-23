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

    public MemoryStoreProviderRogueImpl(String path) {
        this.rogueMap = RogueMap.<String, String>mmap()
                .persistent(Paths.get(path, "memory/rogue.db").toAbsolutePath().toString())
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