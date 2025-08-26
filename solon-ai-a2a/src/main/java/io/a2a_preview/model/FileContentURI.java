package io.a2a_preview.model;

import lombok.Data;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Data
public class FileContentURI implements FileContent {

    /**
     * Name is the optional name of the file
     */
    String name;

    /**
     * MimeType is the optional MIME type of the file content
     */
    String mimeType;

    /**
     * URI is the URI pointing to the file content
     */
    String uri;
}
