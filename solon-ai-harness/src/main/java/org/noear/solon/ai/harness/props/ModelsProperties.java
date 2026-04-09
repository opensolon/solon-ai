package org.noear.solon.ai.harness.props;

import org.noear.snack4.annotation.ONodeAttr;
import org.noear.solon.ai.chat.ChatConfig;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大模型配置属性
 *
 * @author noear
 * @since 3.10.2
 */
public class ModelsProperties implements Serializable {
    @ONodeAttr(name = "default")
    private String _default;
    private Map<String, ChatConfig> list = new LinkedHashMap<>();


    public String getDefault() {
        return _default;
    }

    public void setDefault(String _default) {
        this._default = _default;
    }

    public Map<String, ChatConfig> getList() {
        return list;
    }

    public void setList(Map<String, ChatConfig> list) {
        this.list = list;
    }

    public void add(String name, ChatConfig config) {
        list.put(name, config);
    }

    public ChatConfig getRequired(String name) {
        if (name == null) {
            return list.get(_default);
        } else {
            return list.getOrDefault(name, list.get(_default));
        }
    }
}