package org.noear.solon.ai.media;

import org.noear.solon.Utils;
import org.noear.solon.ai.AiMedia;

/**
 * 虚拟媒体类型
 *
 * @author noear
 * @since 3.2
 */
public abstract class AbstractMedia implements AiMedia {
    protected String b64_json; //就是 base64-str
    protected String url;
    protected String mimeType;

    /**
     * 获取 base64
     */
    public String getB64Json() {
        return b64_json;
    }

    /**
     * 获取 url
     */
    public String getUrl() {
        return url;
    }

    /**
     * 获取 mimeType
     */
    public String getMimeType() {
        return mimeType;
    }

    /**
     * 转为数据字符串
     */
    @Override
    public String toDataString(boolean useMime) {
        if (Utils.isEmpty(getB64Json())) {
            return getUrl();
        } else {
            if (useMime) {
                if (Utils.isNotEmpty(getMimeType())) {
                    return "data:" + mimeType + ";base64," + b64_json;
                }
            }

            return b64_json;
        }
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "{" +
                "url='" + getUrl() + '\'' +
                ", b64_json='" + getB64Json() + '\'' +
                ", mimeType='" + getMimeType() + '\'' +
                '}';
    }
}