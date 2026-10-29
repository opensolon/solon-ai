package features.ai.core;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.MediaBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MediaBlock.filePath 的序列化语义：随历史持久化，但不参与模型请求。
 */
public class MediaBlockFilePathTest {
    @Test
    public void filePathSurvivesJsonRoundTrip() {
        ImageBlock image = ImageBlock.ofBase64("AQ==", "image/png").filePath(".uploads/a.png");
        Contents contents = new Contents();
        contents.addBlock(TextBlock.of("看图"));
        contents.addBlock(image);
        UserMessage message = new UserMessage(contents);

        String json = ChatMessage.toJson(message);
        assertTrue(json.contains(".uploads/a.png"), json);

        UserMessage restored = (UserMessage) ChatMessage.fromJson(json);
        MediaBlock block = (MediaBlock) restored.getBlocks().get(1);
        assertEquals(".uploads/a.png", block.getFilePath());
    }

    @Test
    public void filePathIsAbsentFromRequestData() {
        ImageBlock image = ImageBlock.ofBase64("AQ==", "image/png").filePath(".uploads/a.png");

        //请求只取 data / url / mimeType，filePath 是本地元数据
        assertEquals("AQ==", image.getData());
        assertEquals("data:image/png;base64,AQ==", image.toDataString(true));
        assertNull(ImageBlock.ofUrl("https://cdn/x.png", "image/png").getFilePath());
    }
}
