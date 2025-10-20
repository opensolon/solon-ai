package org.noear.solon.ai.rag.repository.dashvector;

import org.noear.snack4.ONode;
import org.noear.solon.core.serialize.Serializer;

import java.io.IOException;
import java.lang.reflect.Type;

public class StringSerializer implements Serializer<String> {
    /**
     * 内容类型
     */
    @Override
    public String mimeType() {
        return "application/json";
    }

    /**
     * 数据类型
     */
    @Override
    public Class<String> dataType() {
        return String.class;
    }

    /**
     * 序列化器名字
     */
    @Override
    public String name() {
        return "snack3-json";
    }

    /**
     * 序列化
     *
     * @param obj 对象
     */
    @Override
    public String serialize(Object obj) throws IOException {
        return ONode.serialize(obj);
    }

    /**
     * 反序列化
     *
     * @param data   数据
     * @param toType 目标类型
     */
    @Override
    public Object deserialize(String data, Type toType) throws IOException {
        if (toType == null) {
            return ONode.deserialize(data);
        } else {
            return ONode.deserialize(data, toType);
        }
    }
}