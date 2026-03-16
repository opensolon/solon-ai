package org.noear.solon.ai.skills.memory;

import com.yomahub.roguemap.RogueMap;

import java.util.concurrent.TimeUnit;

/**
 *
 * @author noear 2026/3/4 created
 *
 */
public class MemStoreProviderRogueImpl implements MemStoreProvider {
    private final RogueMap<String, String> rogueMap;

    public MemStoreProviderRogueImpl(RogueMap<String, String> rogueMap) {
        this.rogueMap = rogueMap;
//        RogueMap<String, String> map = RogueMap.<String, String>mmap()
//                .persistent("data/mydata.db")
//                .autoExpand(true)
//                .allocateSize(64 * 1024 * 1024L)
//                .keyCodec(StringCodec.INSTANCE)
//                .valueCodec(StringCodec.INSTANCE)
//                .build();
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