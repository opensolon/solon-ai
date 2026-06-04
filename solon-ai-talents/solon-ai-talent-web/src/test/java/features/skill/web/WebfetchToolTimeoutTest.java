package features.skill.web;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.web.WebfetchTalent;

/**
 *
 * @author noear 2026/5/13 created
 *
 */
public class WebfetchToolTimeoutTest {
    WebfetchTalent tool = new WebfetchTalent().retryConfig(1);

    @Test
    public void http_timeout() throws Exception {
        long startTimeMs = System.currentTimeMillis();

        Assertions.assertThrows(Exception.class, () -> {
            Object tmp = tool.webfetch(
                    "https://www.google.com/",
                    "text"
                    , 1_000);

            System.out.println(tmp);
        });

        long spanTimeMs = System.currentTimeMillis() - startTimeMs;
        System.out.println(spanTimeMs);

        assert spanTimeMs < 2_000;
    }

    @Test
    public void http_timeout2() throws Exception {
        long startTimeMs = System.currentTimeMillis();

        Assertions.assertThrows(Exception.class, () -> {
            Object tmp = tool.webfetch(
                    "https://www.google.com/",
                    "text"
                    , null);

            System.out.println(tmp);
        });

        long spanTimeMs = System.currentTimeMillis() - startTimeMs;
        System.out.println(spanTimeMs);

        assert spanTimeMs < 40_000;
    }
}