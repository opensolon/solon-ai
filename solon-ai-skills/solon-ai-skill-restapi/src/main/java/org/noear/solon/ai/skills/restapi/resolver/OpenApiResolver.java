package org.noear.solon.ai.skills.restapi.resolver;

import org.noear.snack4.ONode;
import org.noear.solon.ai.skills.restapi.ApiResolver;
import org.noear.solon.ai.skills.restapi.ApiTool;
import org.noear.solon.lang.Preview;

import java.util.List;

/**
 * OpenAPI 规范解析器（自适应 v2 和 v3）
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class OpenApiResolver implements ApiResolver {
    private static final OpenApiResolver instance = new OpenApiResolver();
    public static OpenApiResolver getInstance() {
        return instance;
    }

    private OpenApiV2Resolver v2Resolver = new OpenApiV2Resolver();
    private OpenApiV3Resolver v3Resolver = new OpenApiV3Resolver();

    @Override
    public String getName() {
        return "OpenApi Resolver";
    }

    @Override
    public List<ApiTool> resolve(String definitionUrl, String source) {
        ONode root = ONode.ofJson(source);
        if (root.hasKey("openapi")) {
            return v3Resolver.doResolve(root);
        }

        return v2Resolver.doResolve(root);
    }
}