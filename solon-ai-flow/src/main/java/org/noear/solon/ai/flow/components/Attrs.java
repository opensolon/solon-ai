package org.noear.solon.ai.flow.components;

/**
 * 组件属性
 *
 * @author noear
 * @since 3.3
 */
public interface Attrs {
    String META_INPUT = "input";
    String META_OUTPUT = "output";
    String META_SESSION = "session";
    String META_TEXT = "text";

    String CTX_SESSION = "session";
    String CTX_MESSAGE = "message";
    String CTX_PROPERTY = "property";

    String PROP_TOOLS = "tools";

    String PROP_EMBEDDING_MODEL = "embeddingModel";
}