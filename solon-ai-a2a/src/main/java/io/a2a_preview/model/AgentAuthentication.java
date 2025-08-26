package io.a2a_preview.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@Data
public class AgentAuthentication {

    /**
     * Schemes is a list of supported authentication schemes
     */
    List<String> schemes;

    /**
     * Credentials for authentication. Can be a string (e.g., token) or null if not required initially
     */
    String credentials;
}
