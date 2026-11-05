package org.noear.solon.ai.harness.session.loop;

import org.noear.solon.scheduling.scheduled.manager.IJobManager;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 只记录 job 名的 IJobManager 桩（不真正调度：测试用 doTrigger 直接驱动轮次）。
 */
final class FakeJobManager {
    final Set<String> jobs = ConcurrentHashMap.newKeySet();
    final IJobManager proxy;

    FakeJobManager() {
        proxy = (IJobManager) Proxy.newProxyInstance(IJobManager.class.getClassLoader(),
                new Class<?>[]{IJobManager.class}, (p, method, args) -> {
                    switch (method.getName()) {
                        case "jobAdd":
                            jobs.add((String) args[0]);
                            return null;
                        case "jobExists":
                            return jobs.contains((String) args[0]);
                        case "jobRemove":
                            jobs.remove((String) args[0]);
                            return null;
                        case "isStarted":
                        case "hasJobInterceptor":
                            return false;
                        case "hashCode":
                            return System.identityHashCode(p);
                        case "equals":
                            return p == args[0];
                        case "toString":
                            return "FakeJobManager";
                        default:
                            return null;
                    }
                });
    }
}
