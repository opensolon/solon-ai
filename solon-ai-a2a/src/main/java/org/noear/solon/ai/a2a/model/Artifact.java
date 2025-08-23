package org.noear.solon.ai.a2a.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Data
public class Artifact {

    /**
     * ArtifactId is the unique identifier for the artifact
     */
    String artifactId;

    /**
     * Name is an optional name for the artifact
     */
    String name;

    /**
     * Description is an optional description of the artifact
     */
    String description;

    /**
     * Parts are the constituent parts of the artifact
     */
    List<Part> parts;

    /**
     * Index is an optional index for ordering artifacts
     */
    Integer index;

    /**
     * Append indicates if this artifact content should append to previous content
     */
    Boolean append;

    /**
     * Metadata is optional metadata associated with the artifact
     */
    Map<String, Object> metadata;

    /**
     * LastChunk indicates if this is the last chunk of data for this artifact
     */
    Boolean lastChunk;
}
