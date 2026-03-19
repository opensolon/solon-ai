package org.noear.solon.ai.skills.browser;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

public class BrowserSkill extends AbsSkill {

    // 核心 JS 标记脚本：为视觉对齐提供编号
    private static final String MARK_JS =
            "() => {" +
                    "  const items = document.querySelectorAll('button, a, input, textarea, select, [role=\"button\"]');" +
                    "  items.forEach((el, i) => {" +
                    "    el.setAttribute('data-solon-id', i);" +
                    "    const rect = el.getBoundingClientRect();" +
                    "    if (rect.width > 0 && rect.height > 0) {" +
                    "      const marker = document.createElement('div');" +
                    "      marker.className = 'solon-marker';" +
                    "      marker.textContent = i;" +
                    "      marker.style = `position:absolute; background:red; color:white; font-size:10px; font-weight:bold; " +
                    "                     padding:2px; border-radius:2px; z-index:2147483647; pointer-events:none; " +
                    "                     top:${window.scrollY + rect.top}px; left:${window.scrollX + rect.left}px;`;" +
                    "      document.body.appendChild(marker);" +
                    "    }" +
                    "  });" +
                    "}";

    private static final String CLEAN_JS =
            "() => {" +
                    "  document.querySelectorAll('.solon-marker').forEach(e => e.remove());" +
                    "}";

    @Override
    public String description() {
        return "高级浏览器技能：支持多标签切换、编号精准定位、文件上传与下载。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String __cwd = prompt.attrAs("__cwd");
        BrowserManager browserManager = BrowserManager.get(__cwd);

        StringBuilder sb = new StringBuilder();
        sb.append("## Browser 指令集\n");
        sb.append("- **视觉对齐**: 调用 `browser_screenshot` 后根据红框数字使用 `[data-solon-id='数字']` 选择器。\n");
        sb.append("- **文件传输**: 下载的文件保存在 `" + ".browser" + "` 目录；上传时需提供项目内的相对路径。\n");

        if (browserManager == null) {
            sb.append("- **当前标签**: ").append("[default]").append("\n");
        } else {
            sb.append("- **当前标签**: ").append(browserManager.getPageMap().keySet()).append("\n");
        }

        sb.append("## 浏览器使用准则\n");
        sb.append("- **适用场景**: 只有当目标地址是 HTML 网页、需要处理复杂的 JavaScript 渲染、或需要进行视觉确认时，才使用浏览器。\n");
        sb.append("- **禁止场景**: 严禁使用浏览器调用 RESTful API 或下载纯文本/JSON 数据。对于此类任务，请优先使用 http 工具。\n");
        sb.append("- **视觉驱动**: 浏览器是一个视觉工具。每次关键操作前，请先执行 `browser_screenshot` 以确保你正盯着正确的页面。\n");
        return sb.toString();
    }

    // --- 标签页管理 ---
    @ToolMapping(name = "browser_tab_create", description = "在浏览器中创建并切换到新标签页。")
    public String createTab(@Param("tab_id") String tabId, String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        browserManager.createPage(tabId);
        return "已创建并切换到: " + tabId;
    }

    @ToolMapping(name = "browser_tab_switch", description = "在浏览器中切换标签页。")
    public String switchTab(@Param("tab_id") String tabId,
                            String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        browserManager.switchPage(tabId);
        return "已切换至: " + tabId;
    }

    @ToolMapping(name = "browser_save_session", description = "将当前的登录状态、Cookies 等持久化到本地。建议在登录操作完成后调用。")
    public String saveSession(String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        try {
            browserManager.saveState();
            return "浏览器会话状态已成功保存。下次启动将自动保持登录。";
        } catch (Throwable e) {
            return "状态保存失败: " + e.getMessage();
        }
    }

    // --- 核心交互 ---
    @ToolMapping(name = "browser_navigate", description = "在浏览器中打开指定页面。仅当需要处理 HTML 页面、执行 JavaScript 或进行视觉观察时使用。")
    public String navigate(@Param("url") String url,
                           String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        Page page = browserManager.getCurrentPage();
        page.navigate(url);
        page.waitForLoadState(LoadState.NETWORKIDLE);
        return String.format("已加载: %s (标题: %s)", page.url(), page.title());
    }

    @ToolMapping(name = "browser_screenshot", description = "捕捉浏览器当前呈现的视觉画面，并附带交互元素编号，用于视觉对齐和确认页面状态。")
    public String screenshot(String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        Page page = browserManager.getCurrentPage();

        // 1. 先执行一次清理，防止之前的标记堆积
        page.evaluate(CLEAN_JS);
        // 2. 注入新标记
        page.evaluate(MARK_JS);

        // 3. 截图
        byte[] buffer = page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG));

        // 4. 立即清理标记，恢复 DOM 原状
        page.evaluate(CLEAN_JS);

        return "data:image/png;base64," + Base64.getEncoder().encodeToString(buffer);
    }

    @ToolMapping(name = "browser_interact", description = "在浏览器中模拟真实用户对网页 DOM 元素进行交互操作。只能操作通过 browser_screenshot 看到的编号元素。")
    public String interact(@Param(name = "action", description = "交互操作 (可选值：click, type, hover, scroll)") String action,
                           @Param(name = "selector") String selector,
                           @Param(value = "text", required = false) String text,
                           String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        Page page = browserManager.getCurrentPage();

        // 操作前清理，确保 selector 选中的是真实元素而非 marker
        page.evaluate(CLEAN_JS);

        try {
            switch (action.toLowerCase()) {
                case "click":
                    page.click(selector);
                    break;
                case "type":
                    page.fill(selector, text);
                    page.keyboard().press("Enter");
                    break;
                case "hover":
                    page.hover(selector);
                    break;
                case "scroll":
                    // 滚动 500 像素
                    page.mouse().wheel(0, 500);
                    // 增加一个微小的延迟，给懒加载图片和 JavaScript 动画一点缓冲时间
                    page.waitForTimeout(800);
                    return "已滚屏，建议重新截图观察新内容";
                default:
                    return "未知动作";
            }
            page.waitForLoadState(LoadState.NETWORKIDLE);
            return "操作成功，当前 URL: " + page.url();
        } catch (Throwable e) {
            return "操作失败: " + e.getMessage();
        }
    }

    // --- 文件上传与下载 (新功能) ---

    @ToolMapping(name = "browser_download", description = "在浏览器中点击链接并捕获下载文件。文件将保存到下载目录。")
    public String download(@Param("selector") String selector,
                           String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        Page page = browserManager.getCurrentPage();
        try {
            Download download = page.waitForDownload(() -> page.click(selector));
            Path targetPath = browserManager.getDownloadPath().resolve(download.suggestedFilename());
            download.saveAs(targetPath);
            return "文件已下载至: " + targetPath.getFileName().toString();
        } catch (Throwable e) {
            return "下载失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "browser_upload", description = "在浏览器中向指定的文件输入框上传本地文件。")
    public String upload(@Param("selector") String selector,
                         @Param("file_path") String filePath,
                         String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        Page page = browserManager.getCurrentPage();
        try {
            // 解析相对工作区的路径
            Path fullPath = Paths.get(__cwd).resolve(filePath).toAbsolutePath();
            page.setInputFiles(selector, fullPath);
            return "文件已成功载入上传框: " + filePath;
        } catch (Throwable e) {
            return "上传失败: " + e.getMessage();
        }
    }
}