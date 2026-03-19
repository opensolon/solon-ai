package org.noear.solon.ai.skills.browser;

import com.microsoft.playwright.*;
import org.noear.solon.core.util.RunUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BrowserManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BrowserManager.class);

    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final Map<String, Page> pageMap = new ConcurrentHashMap<>();
    private String currentPageId = "default";
    private String __cwd;

    private Path statePath;
    private Path downloadPath;

    private static final Map<String, BrowserManager> cached = new ConcurrentHashMap<>();

    public static BrowserManager of(String __cwd) {
        return cached.computeIfAbsent(__cwd, k -> new BrowserManager(k));
    }

    public static BrowserManager get(String __cwd) {
        return cached.get(__cwd);
    }

    public static void closeAll() {
        for (BrowserManager item : cached.values()) {
            item.close();
        }
    }

    private BrowserManager(String __cwd) {
        this.__cwd = __cwd;
        this.playwright = Playwright.create();

        // 状态文件存储路径：项目根目录/.soloncode/browser_state.json

        this.downloadPath = Paths.get(__cwd, ".browser").toAbsolutePath();
        this.statePath = Paths.get(__cwd, ".browser", "_browser_state.json");

        try {
            // 自动创建必要的目录
            Files.createDirectories(this.downloadPath);
            Files.createDirectories(this.statePath.getParent());
        } catch (Throwable ignored) {
        }

        // ...

        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setDownloadsPath(downloadPath)
                .setArgs(Arrays.asList("--disable-blink-features=AutomationControlled"));

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWindows) {
            launchOptions.setChannel("msedge");
        } else {
            launchOptions.setChannel("chrome");
        }

        this.browser = playwright.chromium().launch(launchOptions);

        // 配置 Context 选项
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setViewportSize(1280, 800)
                .setAcceptDownloads(true)
                .setUserAgent("Mozilla/5.0 SolonCode/1.0 (AI Agent)");

        // 如果存在历史状态文件，则加载它（包含 Cookies 和 LocalStorage）
        if (Files.exists(statePath)) {
            options.setStorageStatePath(statePath);
        }

        this.context = browser.newContext(options);

        Page defaultPage = context.newPage();
        pageMap.put("default", defaultPage);
    }

    /**
     * 手动触发状态保存（例如 AI 完成登录后调用）
     */
    public void saveState() {
        context.storageState(new BrowserContext.StorageStateOptions()
                .setPath(statePath));
    }

    public Page getCurrentPage() {
        return pageMap.get(currentPageId);
    }

    public Path getDownloadPath() {
        return downloadPath;
    }

    public Map<String, Page> getPageMap() {
        return pageMap;
    }

    public void switchPage(String pageId) {
        if (pageMap.containsKey(pageId)) {
            this.currentPageId = pageId;
        } else {
            throw new IllegalArgumentException("未找到标签页: " + pageId);
        }
    }

    public void createPage(String pageId) {
        Page newPage = context.newPage();
        pageMap.put(pageId, newPage);
        this.currentPageId = pageId;
    }

    @Override
    public void close() {
        cached.remove(__cwd);
        saveState(); // 关闭前自动保存
        for (Page page : pageMap.values()) {
            RunUtil.runAndTry(page::close);
        }
        if (context != null) RunUtil.runAndTry(context::close);
        if (browser != null) RunUtil.runAndTry(browser::close);
        if (playwright != null) RunUtil.runAndTry(playwright::close);
    }
}