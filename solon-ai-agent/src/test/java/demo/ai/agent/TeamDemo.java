package demo.ai.agent;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.chat.ChatModel;

/**
 *
 * @author noear 2026/1/10 created
 *
 */
public class TeamDemo {
    @Test
    public void demo() {
        ChatModel chatModel = LlmUtil.getChatModel();
        ReActAgent agent_a = ReActAgent.of(chatModel).name("a").outputKey("a").build();
        ReActAgent agent_b = ReActAgent.of(chatModel).name("b").outputKey("b").build();

        TeamAgent agent_c = TeamAgent.of(chatModel).name("c").outputKey("c")
                .addAgent(TeamAgent.of(chatModel)
                        .name("c1")
                        .addAgent(ReActAgent.of(chatModel).name("c1-1").build())
                        .build())
                .addAgent(ReActAgent.of(chatModel).name("c2").build())
                .addAgent(ReActAgent.of(chatModel).name("c3").build())
                .addAgent(ReActAgent.of(chatModel).name("c4").build())
                .addAgent(ReActAgent.of(chatModel).name("c5").build())
                .build();

        ReActAgent agent_d = ReActAgent.of(chatModel).name("d").outputKey("d").build();
        ReActAgent agent_e = ReActAgent.of(chatModel).name("e").outputKey("e").build();
        ReActAgent agent_f = ReActAgent.of(chatModel).name("f").outputKey("f").build();

        ReActAgent agent_g = ReActAgent.of(chatModel).name("g").outputKey("g").build();
        ReActAgent agent_h = ReActAgent.of(chatModel).name("h").outputKey("h").build();
        ReActAgent agent_i = ReActAgent.of(chatModel).name("i").outputKey("i").build();


        TeamAgent teamAgent = TeamAgent.of(chatModel)
                .name("g1")
                .graphAdjuster(spec -> {
                    spec.addStart("start").linkAdd(agent_a.name());
                    spec.addActivity(agent_a).linkAdd(agent_b.name());
                    spec.addActivity(agent_b).linkAdd("exc1");
                    spec.addExclusive("exc1")
                            .linkAdd(agent_b.name(), l -> l.when("route=b"))
                            .linkAdd("exc2");
                    spec.addExclusive("exc2")
                            .linkAdd(agent_c.name(), l -> l.when("route=c"))
                            .linkAdd(agent_d.name());
                    spec.addActivity(agent_c).linkAdd("exc2");
                    spec.addActivity(agent_d).linkAdd("exc3");
                    spec.addExclusive("exc3")
                            .linkAdd(agent_c.name(), l -> l.when("route=c"))
                            .linkAdd(agent_e.name());
                    spec.addActivity(agent_e).linkAdd(agent_f.name());
                    spec.addActivity(agent_f).linkAdd("exc4");
                    spec.addExclusive("exc4")
                            .linkAdd(agent_g.name(), l -> l.when("route=g"))
                            .linkAdd(agent_i.name(), l -> l.when("route=i"))
                            .linkAdd("end");
                    spec.addActivity(agent_g).linkAdd(agent_h.name());
                    spec.addActivity(agent_h).linkAdd("end");
                    spec.addActivity(agent_i).linkAdd("end");
                    spec.addEnd("end");
                }).build();

        String yaml = teamAgent.getGraph().toYaml();
        System.out.println(yaml);
    }
}