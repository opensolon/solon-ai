package features.ai.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.prompt.PromptImpl;

import java.io.*;
import java.util.*;

/**
 * PromptImpl 高强度单元测试 (目标 100% 覆盖率)
 */
public class PromptTest {

    @Test
    @DisplayName("1. 基础属性与 Attr 相关方法测试")
    public void testAttributes() {
        Prompt prompt = new PromptImpl();

        // attrPut & attr
        prompt.attrPut("k1", "v1");
        Assertions.assertEquals("v1", prompt.attr("k1"));

        // attrAs
        Assertions.assertEquals("v1", (String) prompt.attrAs("k1"));

        // attrOrDefault
        Assertions.assertEquals("v1", prompt.attrOrDefault("k1", "v2"));
        Assertions.assertEquals("v2", prompt.attrOrDefault("k2", "v2"));

        // attrPut(Map) - 覆盖有效路径
        Map<String, Object> map = new HashMap<>();
        map.put("k3", "v3");
        prompt.attrPut(map);
        Assertions.assertEquals("v3", prompt.attr("k3"));

        // Assert.isNotEmpty 覆盖 - 传入 null 或空 Map
        prompt.attrPut(null);
        prompt.attrPut(new HashMap<>());
        Assertions.assertEquals(2, prompt.attrs().size());
    }

    @Test
    @DisplayName("2. 消息添加与意图提取逻辑 (含边界与跳过空消息分支)")
    public void testMessageAndContent() {
        Prompt prompt = new PromptImpl();

        Assertions.assertTrue(prompt.isEmpty());
        Assertions.assertTrue(Prompt.isEmpty(null));

        // addMessage(String)
        prompt.addMessage("user1");
        Assertions.assertEquals("user1", prompt.getUserContent());

        // 核心优化：测试 getUserContent 逆序搜索时跳过 content 为空的 USER 消息
        // 手动注入一条 content 为空的 User 消息
        prompt.addMessage(ChatMessage.ofUser(""));
        Assertions.assertEquals("user1", prompt.getUserContent()); // 应该跳过空的，依然返回 user1

        // getSystemContent 缓存命中
        prompt.addMessage(ChatMessage.ofSystem("sys1"));
        Assertions.assertEquals("sys1", prompt.getSystemContent());

        // 验证 addMessage 不会导致 system 缓存失效
        prompt.addMessage(ChatMessage.ofSystem("sys2"));
        Assertions.assertEquals("sys1", prompt.getSystemContent());

        // add 空值处理覆盖
        prompt.addMessage((String) null);
        prompt.addMessage((Collection<ChatMessage>) null);
        Assertions.assertEquals(4, prompt.getMessages().size()); // user1, empty, sys1, sys2 (null 被过滤)
    }

    @Test
    @DisplayName("3. replaceMessages 逻辑与全量失效")
    public void testReplaceMessages() {
        Prompt prompt = new PromptImpl();
        prompt.addMessage("old");
        prompt.getSystemContent();

        prompt.replaceMessages(Arrays.asList(ChatMessage.ofSystem("new_sys")));
        Assertions.assertEquals("new_sys", prompt.getSystemContent());
        Assertions.assertNull(prompt.getUserContent());

        prompt.replaceMessages(null);
        Assertions.assertTrue(prompt.isEmpty());
    }

    @Test
    @DisplayName("4. 视图安全性测试")
    public void testReadOnlyView() {
        Prompt prompt = new PromptImpl();
        prompt.getMessages(); // 触发 lazy init
        Assertions.assertThrows(UnsupportedOperationException.class, () -> prompt.getMessages().clear());
    }

    @Test
    @DisplayName("5. 原生序列化测试 (Java Serializable 验证)")
    public void testJavaSerialization() throws Exception {
        PromptImpl original = new PromptImpl();
        original.attrPut("id", "123");
        original.addMessage(ChatMessage.ofSystem("sys"));
        original.addMessage("hello");
        original.getUserContent(); // 产生缓存

        // 原生序列化
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        new ObjectOutputStream(bos).writeObject(original);

        // 反序列化
        PromptImpl deserialized = (PromptImpl) new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray())).readObject();

        // 验证 transient 字段已清空且能懒加载还原
        Assertions.assertEquals("123", deserialized.attr("id"));
        Assertions.assertEquals("hello", deserialized.getUserContent());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> deserialized.getMessages().clear());
    }

    @Test
    @DisplayName("6. ONode 序列化测试 (JSON 验证)")
    public void testJsonSerialization() {
        PromptImpl original = new PromptImpl();
        original.attrPut("id", "123");
        original.addMessage("hello");

        String json = ONode.serialize(original, Feature.Write_ClassName);
        Prompt deserialized = ONode.deserialize(json, Feature.Read_AutoType);

        Assertions.assertEquals("123", deserialized.attr("id"));
        Assertions.assertEquals("hello", deserialized.getUserContent());
    }

    @Test
    @DisplayName("7. 静态构建工厂与边界测试")
    public void testStaticAndEdge() {
        Assertions.assertNotNull(Prompt.of("msg"));
        Assertions.assertNotNull(Prompt.of(ChatMessage.ofUser("msg")));
        Assertions.assertNotNull(Prompt.of(Collections.singletonList(ChatMessage.ofUser("msg"))));

        // 没有任何用户消息时的分支
        Prompt emptyUser = new PromptImpl().addMessage(ChatMessage.ofAssistant("hi"));
        Assertions.assertNull(emptyUser.getUserContent());

        // 没有任何系统消息时的分支
        Assertions.assertNull(emptyUser.getSystemContent());
    }
}