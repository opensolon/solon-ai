package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * FilePart represents a File segment within parts
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Data
@AllArgsConstructor
public class FilePart implements Part {
    /**
     * Kind is the part type - file for FileParts
     */
    String kind;
    
    /**
     * File is the file content either as url or bytes
     */
    FileContent file;

    /**
     * Metadata is optional metadata associated with the part
     */
    Map<String, Object> metadata;


    public FilePart(FileContent file, Map<String, Object> metadata) {
        this("file", file, metadata);
    }

    public FilePart(FileContent file) {
        this("file", file, null);
    }
    

}