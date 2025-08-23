package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class FileContentBytes implements FileContent {

    /**
     * Name is the optional name of the file
     */
    String name;

    /**
     * MimeType is the optional MIME type of the file content
     */
    String mimeType;

    /**
     * Bytes is the file content encoded as a Base64 string
     */
    String bytes;
}
