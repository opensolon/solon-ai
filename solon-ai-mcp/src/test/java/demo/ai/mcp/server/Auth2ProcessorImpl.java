package demo.ai.mcp.server;

import org.noear.solon.Utils;
import org.noear.solon.auth.AuthProcessor;
import org.noear.solon.auth.annotation.Logical;
import org.noear.solon.core.handle.Context;

import java.util.Arrays;

/**
 *
 * @author noear 2025/12/16 created
 *
 */
public class Auth2ProcessorImpl implements AuthProcessor {
    @Override
    public boolean verifyIp(String ip) {
        return false;
    }

    @Override
    public boolean verifyLogined() {
        return false;
    }

    @Override
    public boolean verifyPath(String path, String method) {
        return false;
    }

    @Override
    public boolean verifyPermissions(String[] permissions, Logical logical) {
        return false;
    }

    @Override
    public boolean verifyRoles(String[] roles, Logical logical) {
        Context ctx = Context.current();
        if (ctx == null) {
            return false;
        } else {
            String role = ctx.header("role");
            if (Utils.isEmpty(role)) {
                return false;
            }

            return Arrays.asList(roles).contains(role);
        }
    }
}
