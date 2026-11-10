package features.ai.i18n;

import java.util.Locale;

/**
 * i18n 测试辅助
 */
final class I18nTestSupport {
    private I18nTestSupport() {
    }

    /**
     * 运行时唯一语言
     *
     * <p>{@code AgentI18n} 的“已知语言”是进程级状态，用例之间会相互影响；需要验证
     * “是否被登记”时用一个本次运行才出现的语言，避免依赖用例顺序。</p>
     */
    static Locale uniqueLocale() {
        long n = System.nanoTime();
        StringBuilder sb = new StringBuilder("qq");
        for (int i = 0; i < 5; i++) {
            sb.append((char) ('a' + (int) (n % 26)));
            n /= 26;
        }

        return new Locale(sb.toString());
    }
}
