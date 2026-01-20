package demo.ai;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleInterceptor;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.tool.FunctionTool;

import java.util.Map;

/**
 *
 * @author noear 2026/1/20 created
 *
 */
public class Show {
    public static void main(String[] args) throws Throwable {
        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama")
                .model("qwen2.5:1.5b")
                .defaultOptionSet("a", "b")
                .defaultToolAdd(new Tool1())
                .defaultToolContextPut("x", "y")
                .defaultSkillAdd(new Skill1())
                .defaultInterceptorAdd(new ChatInterceptor() {
                })
                .build();

        chatModel.prompt("xxx")
                .options(o -> {
                    o.optionSet("a","b");
                    o.toolAdd(new Tool1());
                    o.toolContextPut("x","y");
                    o.skillAdd(new Skill1());
                    o.interceptorAdd(new ChatInterceptor() {
                    });
                })
                .call();

        ReActAgent reActAgent = ReActAgent.of(chatModel)
                .chatOptions(o -> {
                    o.optionSet("a","b");

                    o.toolAdd(new Tool1());
                    o.toolContextPut("x","y");
                    o.skillAdd(new Skill1());
                    o.interceptorAdd(new ChatInterceptor() {
                    });
                })
                .defaultToolAdd(new Tool1())
                .defaultToolContextPut("x","y")
                .defaultSkillAdd(new Skill1())
                .defaultInterceptorAdd(new ReActInterceptor(){})
                .build();

        reActAgent.prompt("xxx")
                .options(o->{
                    o.toolAdd(new Tool1());
                    o.toolsContextPut("x","y");
                    o.skillAdd(new Skill1());
                    o.interceptorAdd(new ReActInterceptor(){});
                })
                .call();

        SimpleAgent simpleAgent = SimpleAgent.of(chatModel)
                .defaultToolAdd(new Tool1())
                .defaultToolContextPut("x","y")
                .defaultSkillAdd(new Skill1())
                .defaultInterceptorAdd(new SimpleInterceptor(){})
                .build();

        simpleAgent.prompt("xxx")
                .options(o->{
                    o.optionSet("a","b");
                    o.toolAdd(new Tool1());
                    o.toolContextPut("x","y");
                    o.skillAdd(new Skill1());
                    o.interceptorAdd(new SimpleInterceptor(){});
                })
                .call();

        TeamAgent teamAgent = TeamAgent.of(chatModel)
                .defaultToolAdd(new Tool1())
                .defaultToolContextPut("x","y")
                .defaultInterceptorAdd(new TeamInterceptor(){})
                .build();

        teamAgent.prompt("xxx")
                .options(o->{
                    o.interceptorAdd(new TeamInterceptor(){});
                }).call();
    }

    public static class Skill1 implements Skill {

    }

    public static class Tool1 implements FunctionTool {
        @Override
        public String name() {
            return "";
        }

        @Override
        public String title() {
            return "";
        }

        @Override
        public String description() {
            return "";
        }

        @Override
        public boolean returnDirect() {
            return false;
        }

        @Override
        public String inputSchema() {
            return "";
        }

        @Override
        public String handle(Map<String, Object> args) throws Throwable {
            return "";
        }
    }
}
