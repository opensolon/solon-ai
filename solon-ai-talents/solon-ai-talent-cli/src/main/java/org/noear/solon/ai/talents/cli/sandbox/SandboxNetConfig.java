/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.cli.sandbox;

import java.util.Collections;
import java.util.List;

/**
 * 沙盒网络配置
 *
 * <p>网络策略（allow-only，与 Anthropic sandbox-runtime 一致）：</p>
 * <ul>
 *   <li>allowedDomains: 允许的域名（支持 *.example.com 通配符）。空列表 = 禁止所有网络。</li>
 *   <li>deniedDomains: 禁止的域名（优先级高于 allowedDomains）</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.1
 */
public class SandboxNetConfig {
    private List<String> allowedDomains = Collections.emptyList();
    private List<String> deniedDomains = Collections.emptyList();

    public List<String> getAllowedDomains() {
        return allowedDomains;
    }

    public void setAllowedDomains(List<String> allowedDomains) {
        this.allowedDomains = allowedDomains != null ? allowedDomains : Collections.emptyList();
    }

    public List<String> getDeniedDomains() {
        return deniedDomains;
    }

    public void setDeniedDomains(List<String> deniedDomains) {
        this.deniedDomains = deniedDomains != null ? deniedDomains : Collections.emptyList();
    }

    /**
     * 检查给定域名是否被允许
     */
    public boolean isDomainAllowed(String host) {
        if (host == null) return false;
        String lowerHost = host.toLowerCase();

        // 先检查黑名单
        for (String denied : deniedDomains) {
            if (matchesDomain(lowerHost, denied.toLowerCase())) {
                return false;
            }
        }

        // 白名单为空 = 禁止所有
        if (allowedDomains.isEmpty()) {
            return false;
        }

        // 检查白名单
        for (String allowed : allowedDomains) {
            if (matchesDomain(lowerHost, allowed.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesDomain(String host, String pattern) {
        if (pattern.startsWith("*.")) {
            return host.endsWith(pattern.substring(1)) || host.equals(pattern.substring(2));
        }
        return host.equals(pattern);
    }
}
