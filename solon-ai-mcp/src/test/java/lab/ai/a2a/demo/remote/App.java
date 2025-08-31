package lab.ai.a2a.demo.remote;

import org.noear.solon.Solon;

/**
 * @author noear 2025/8/31 created
 */
public class App {
    public static void main(String[] args) {
        Solon.start(App.class, new String[]{"--server.port=9001"});
    }
}
