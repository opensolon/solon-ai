package org.noear.solon.ai.a2a.model;

import lombok.Data;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Data
public class FileContentBase {

    /**
     * Name is the optional name of the file
     */
    String name;

    /**
     * MimeType is the optional MIME type of the file content
     */
    String mimeType;
}
