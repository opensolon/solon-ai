package features.skill.web;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.web.WebsearchTalent;

/**
 *
 * @author noear 2026/8/8 created
 *
 */
public class WebsearchTalentTest {
    @Test
    public void case1() throws Throwable {
        WebsearchTalent websearchTalent = new WebsearchTalent();

        System.out.println(websearchTalent.websearch("solon", null));
    }
}