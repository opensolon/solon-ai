package features.ai.core;

import org.junit.jupiter.api.Test;

/**
 *
 * @author noear 2026/2/14 created
 *
 */
public class StringTest {
    @Test
    public void case1(){
        String a = "a";
        StringBuilder a1 = new StringBuilder();
        a1.append("a");

        assert a.contentEquals(a1);
    }
}
