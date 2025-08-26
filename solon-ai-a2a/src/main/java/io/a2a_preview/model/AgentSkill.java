package io.a2a_preview.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Builder
@AllArgsConstructor
@Data
public class AgentSkill {

    /**
     * ID is the unique identifier for the skill
     */
    String id;

    /**
     * Name is the human-readable name of the skill
     */
    String name;

    /**
     * Description is an optional description of the skill
     */
    String description;

    /**
     * Tags is an optional list of tags associated with the skill for categorization
     */
    List<String> tags;

    /**
     * Examples is an optional list of example inputs or use cases for the skill
     */
    List<String> examples;

    /**
     * InputModes is an optional list of input modes supported by this skill
     */
    List<String> inputModes;

    /**
     * OutputModes is an optional list of output modes supported by this skill
     */
    List<String> outputModes;
}
