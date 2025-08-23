package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * TextPart represents a text segment within parts
 * @author by HaiTao.Wang on 2025/8/21.
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
public class TextPart implements Part {
    /**
     * Kind is the part type - text for TextParts
     */
    String kind;
    
    /**
     * Text is the text content
     */
    String text;

    Map<String, Object> metadata;

    public TextPart(String text) {
        this("text", text, null);
    }

}