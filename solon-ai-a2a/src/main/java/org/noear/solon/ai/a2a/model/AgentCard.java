package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@Data
public class AgentCard {

    /**
     * Name is the name of the agent
     */
    String name;

    /**
     * Description is an optional description of the agent
     */
    String description;

    /**
     * URL is the base URL endpoint for interacting with the agent
     */
    String url;

    /**
     * Provider is information about the provider of the agent
     */
    AgentProvider provider;

    /**
     * Version is the version identifier for the agent or its API
     */
    String version;

    /**
     * DocumentationURL is an optional URL pointing to the agent's documentation
     */
    String documentationUrl;

    /**
     * Capabilities are the capabilities supported by the agent
     */
    AgentCapabilities capabilities;

    /**
     * Authentication details required to interact with the agent
     */
    AgentAuthentication authentication;

    /**
     * DefaultInputModes are the default input modes supported by the agent
     */
    List<String> defaultInputModes;

    /**
     * DefaultOutputModes are the default output modes supported by the agent
     */
    List<String> defaultOutputModes;

    /**
     * Skills is the list of specific skills offered by the agent
     */
    List<AgentSkill> skills;
}
