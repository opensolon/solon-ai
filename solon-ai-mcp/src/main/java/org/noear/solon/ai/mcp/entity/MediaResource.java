package org.noear.solon.ai.mcp.entity;

import org.noear.solon.Utils;
import org.noear.solon.ai.AiMedia;
import org.noear.solon.ai.audio.Audio;
import org.noear.solon.ai.image.Image;
import org.noear.solon.ai.video.Video;

/**
 * 媒体资源
 *
 * @author noear
 * @since 3.1
 */
public class MediaResource extends SimpleResource implements AiMedia {
    private String mimeType;

    public MediaResource(boolean isBase64, String content, String mimeType) {
        super(isBase64, content);
        this.mimeType = mimeType;
    }

    /**
     * 媒体类型
     */
    public String mimeType() {
        return mimeType;
    }

    @Override
    public String toDataString(boolean useMime) {
        if (useMime) {
            if (Utils.isNotEmpty(mimeType)) {
                return "data:" + mimeType + ";base64," + content();
            }
        }

        return content();
    }

    public Image toImage() {
        if (isBase64()) {
            return Image.ofBase64(content(), mimeType());
        } else {
            return Image.ofUrl(content());
        }
    }

    public Audio toAudio() {
        if (isBase64()) {
            return Audio.ofBase64(content(), mimeType());
        } else {
            return Audio.ofUrl(content());
        }
    }

    public Video toVideo() {
        if (isBase64()) {
            return Video.ofBase64(content(), mimeType());
        } else {
            return Video.ofUrl(content());
        }
    }
}