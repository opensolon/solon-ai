package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * DataPart represents a structured data segment within a message part
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Data
@AllArgsConstructor
public class DataPart implements Part {
    /**
     * Kind is the part type - data for DataParts
     */
    String kind;

    /**
     * Data is the structured data content
     */
    Map<String, Object> data;

    /**
     * Metadata is optional metadata associated with the part
     */
    Map<String, Object> metadata;


    public DataPart(Map<String, Object> data, Map<String, Object> metadata) {
        this("data", data, metadata);
    }

    public DataPart(Map<String, Object> data) {
        this("data", data, null);
    }



}