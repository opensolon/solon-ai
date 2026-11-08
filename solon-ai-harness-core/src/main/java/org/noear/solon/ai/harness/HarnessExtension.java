/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness;

import org.noear.solon.ai.AiSwitchable;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.lang.Preview;

import java.util.Collection;
import java.util.Collections;

/**
 * Harness 扩展接口
 *
 * <p>把「引擎生命周期、代理装配、输入入口、请求装配、会话中断、会话状态、会话删除」切成若干回调点，
 * 另有两个声明类方法（{@link #isQuiescent}、{@link #getToolNames}）。扩展只需覆写自己要用的那几个。
 * 回调点按发生顺序排列（清单由 {@code HarnessExtensionHooksDocTest} 守住）：</p>
 *
 * <pre>
 * build()/addExtension ──onAttach────────────────&gt; [引擎就绪，可注册命令/资源]
 *
 * 每次构建代理     ──onAgentBuild──────────&gt; [ReActAgent 实例]
 *
 * 每次输入进引擎   ──onInput──────────────&gt; [HITL / 续流 / 命令 / 委派解析]
 *
 * 每次请求装配     ──onRequestAssemble─────&gt; [ReActRequest]
 *
 * 用户级中断       ──onSessionInterrupt────&gt; [先于停流：清积压等]
 *
 * 读取会话状态     ──contributeSessionStatus&gt; [贡献插件自定义的事实键]
 *
 * 永久删除会话     ──onSessionRemove───────&gt; [撤掉该会话的定时任务等]
 *
 * remove/shutdown  ──onDetach─────────────&gt; [引擎级清理]
 * </pre>
 *
 * <h3>命名约定</h3>
 * <ul>
 *     <li>{@code on*}：引擎向扩展<b>发通知</b>（扩展观察或参与）。</li>
 *     <li>{@code contribute*}：引擎向扩展<b>收集信息</b>（扩展往组装器里贡献事实，不得有副作用）。</li>
 *     <li>{@code onSession*} / {@code contributeSession*}：会话级回调；其余为引擎级、代理级或请求级。</li>
 * </ul>
 *
 * <h3>插件化协作规则（各管一类，不要混用）</h3>
 * <ol>
 *     <li><b>引擎通知或征集所有扩展 → 用本接口的钩子</b>（一对多广播，如 {@link #onSessionInterrupt}、
 *         {@link #onSessionRemove}；一对多汇集，如 {@link #contributeSessionStatus}）。</li>
 *     <li><b>一个扩展向引擎或另一个扩展提供服务 → 用核心定义的能力接口</b>，实现类同时实现它，
 *         消费方用 {@code engine.getExtension(能力接口.class)} 查找（如
 *         {@link org.noear.solon.ai.harness.spi.InputBacklog}）。不要把领域能力做成本接口的
 *         default 方法——那会让每个扩展都继承与自己无关的语义。</li>
 *     <li><b>需要在会话锁内或无引擎场景读取的会话事实 → 用核心持有的 attrs 槽位</b>
 *         （如 {@link org.noear.solon.ai.harness.spi.RunDispatch}）。</li>
 *     <li><b>核心的静态 API 只读核心事实</b>；扩展贡献的事实一律经引擎读取
 *         （插件事实只在 {@code engine.status(session)} 里，不在 {@code SessionStatus.of(session)} 里）。</li>
 *     <li><b>内核不定义插件事实键</b>：事实键由拥有它的插件定义。内核自己的判定若要依赖插件，
 *         走能力接口的类型化方法（如受理的先进先出走 {@code InputBacklog#hasPending}），而不是「实现方必须贡献某个键」
 *         这种编译器查不到的约定。</li>
 * </ol>
 * <p>插件自有的会话属性键登记在各自模块的 {@code XxxAttrs}（{@code session.<插件>.*}），
 * 核心 {@code HarnessAttrs} 只登记引擎自己的键与核心持有的槽位。</p>
 * <p>核心模块不得 import 任何插件包；插件之间也不互相 import，只经核心契约协作。</p>
 *
 * <h3>开关（{@link #isEnabled()}）语义：只有纯运行期钩子看</h3>
 * <table border="1">
 *     <caption>各钩子是否受 {@link #isEnabled()} 控制</caption>
 *     <tr><th>钩子</th><th>是否判 {@code isEnabled()}</th><th>原因</th></tr>
 *     <tr><td>{@link #onAttach}</td><td><b>否</b></td>
 *         <td>引擎级装配必须完成，否则命令没注册、运行期改回 {@code true} 也挂不上</td></tr>
 *     <tr><td>{@link #onAgentBuild}</td><td><b>否</b></td>
 *         <td>停用由扩展自己在 {@code setEnabled} 里传播给子组件（Talent/Interceptor/Command），
 *             「挂上但停用」比「不挂」更容易在运行期恢复</td></tr>
 *     <tr><td>{@link #onDetach}</td><td><b>否</b></td>
 *         <td>清理不得被开关阻止，否则停用前占用的资源永远无法释放</td></tr>
 *     <tr><td>{@link #onSessionInterrupt}</td><td><b>否</b></td>
 *         <td>同属清理类：停用前积压的任务也必须随用户停止一起作废</td></tr>
 *     <tr><td>{@link #onSessionRemove}</td><td><b>否</b></td>
 *         <td>同属清理类：停用前为该会话建的定时任务也必须随会话一起撤掉</td></tr>
 *     <tr><td>{@link #contributeSessionStatus}</td><td><b>否</b></td>
 *         <td>事实类：停用不代表已有的事实消失（停用前已入队的任务仍在队列里）</td></tr>
 *     <tr><td>{@link #onInput}</td><td>是</td><td>纯运行期钩子，停用即不参与本次输入</td></tr>
 *     <tr><td>{@link #onRequestAssemble}</td><td>是</td><td>纯运行期钩子，停用即不参与本次请求</td></tr>
 *     <tr><td>{@link #isQuiescent}</td><td><b>否</b></td>
 *         <td>事实类：停用前建的定时任务仍然存在</td></tr>
 *     <tr><td>{@link #getToolNames}</td><td><b>否</b></td>
 *         <td>声明类：工具是否可用跟随扩展是否挂载</td></tr>
 * </table>
 *
 * <h3>实现约定</h3>
 * <ul>
 *     <li>本接口的方法<b>全部 default</b>：只覆写自己需要的那几个，不必实现空方法。</li>
 *     <li>需要运行期开关能力时继承 {@link AbsHarnessExtension}（已实现 {@link #isEnabled()}/
 *         {@link #setEnabled(Boolean)}）；直接 {@code implements} 时 {@link AiSwitchable} 的默认实现是
 *         {@code isEnabled() = true} 且 {@code setEnabled} 空操作——即恒启用且无法关闭，
 *         达到「不判 isEnabled 的钩子不受开关影响」的效果（本扩展的实现类，如
 *         {@code GoalExtension}，均继承 {@code AbsHarnessExtension}）。</li>
 *     <li>钩子内对<b>引擎的重入调用是安全的</b>（{@code addExtension}/{@code removeExtension} 用
 *         可重入锁且对尚未构建的主代理会跳过重建），但不要在钩子里长期阻塞。</li>
 *     <li><b>埋点边界</b>：{@link #onInput} / {@link #onRequestAssemble} 只覆盖经引擎入口
 *         （{@code handle} / {@code prompt}）的流量；宿主绕过引擎直接调代理实例不会触发。</li>
 * </ul>
 *
 * @author noear
 * @since 3.10.4
 */
@Preview("3.10")
public interface HarnessExtension extends AiSwitchable {
    /**
     * 挂到引擎上（引擎级初始化）
     *
     * <p><b>调用点</b>：{@code Builder.build()} 一次；{@code HarnessEngine.addExtension(...)} 再一次。</p>
     *
     * <p><b>时序保证</b>：此刻引擎的全部服务（Talent / Catalog / Mount / MCP / LSP / CommandRegistry）
     * 均已装配完毕，任意引擎方法都可安全调用；主代理仍可能是懒加载状态。</p>
     *
     * <p><b>典型用途</b>：注册命令（{@code engine.getCommandRegistry().register(...)}）、
     * 注册挂载与外部连接、建调度器、预注册会话监听器。</p>
     *
     * <p><b>注意</b>：不判 {@code isEnabled()}——停用的扩展也要完成装配；
     * 抛出异常会让引擎构建 <i>fail-fast</i>（不会静默吞掉）。
     * 同一实例重复 {@code extensionAdd} / {@code addExtension} 只算一次（引擎按引用查重），不会重复回调；
     * 同类的不同实例各自挂载（按类型去重用 {@code addExtensionIfAbsent}）。一个实例只应挂到一个引擎上。</p>
     *
     * @param engine 引擎（此刻已装配完毕）
     * @since 4.2.0（原名 {@code initialize}）
     */
    default void onAttach(HarnessEngine engine) {

    }

    /**
     * 从引擎上摘除（引擎级清理）
     *
     * <p><b>调用点</b>：{@code HarnessEngine.removeExtension(...)}——在扩展已从引擎列表移除之后回调，
     * 此时它不会再收到任何 {@link #onInput} / {@link #onRequestAssemble}；
     * 另由 {@code HarnessEngine.shutdown()} 在引擎释放时对<b>所有</b>仍挂着的扩展回调一次
     * （逐个 try/catch，单个扩展的清理异常不会打断其余扩展与执行器回收）。
     * {@code null} 直接返回；移除一个<b>本就不在列表里</b>的实例时回调仍会发生（不校验成员身份）。</p>
     *
     * <p><b>典型用途</b>：注销/停止本扩展在 {@link #onAttach} 里创建的引擎级资源，
     * 尤其是<b>引擎外部</b>的持有者（如 JVM 单例调度器里的 job、独立的线程池、长连接）。
     * 这类资源不清理就会带着已废弃的引擎引用在进程内空转，而宿主无从感知。</p>
     *
     * <p><b>注意</b>：不判 {@code isEnabled()}（清理不得被开关阻止）；
     * 实现应保持<b>幂等</b>（removeExtension 后可能紧跟 shutdown）与<b>静默</b>
     * （需自行 try/catch 内部异常，勿向外抛）。</p>
     *
     * @param engine 引擎（即将释放）
     * @since 4.2.0
     */
    default void onDetach(HarnessEngine engine) {

    }

    /**
     * 每次构建代理时（代理级配置）
     *
     * <p><b>调用点</b>：{@code AgentFactory.create(...)} 末尾，<b>无条件</b>回调——
     * 主代理每次重建（改模型/开关扩展/改配置都会触发）都过一次；
     * 子代理也是按请求新建（如 {@code task} 委派），因此本钩子属<b>热路径，实现须廉价</b>
     * （只做 builder 上的增删，不做 IO，不做重初始化）。</p>
     *
     * <p><b>典型用途</b>：{@code agentBuilder.defaultTalentAdd(...)} /
     * {@code defaultInterceptorAdd(...)} / {@code systemPrompt(...)} 等代理级设置。</p>
     *
     * <p><b>区分主/子代理</b>：用 {@code AgentDefinition.AGENT_MAIN.equals(agentName)} 判断，
     * 无需额外参数。</p>
     *
     * <p><b>注意</b>：不判 {@code isEnabled()}（见类注释）。</p>
     *
     * @param engine       引擎
     * @param agentName    本次构建的代理名
     * @param agentBuilder 代理构建器
     * @since 4.2.0（原名 {@code configure}）
     */
    default void onAgentBuild(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {

    }

    /**
     * 输入进入引擎（输入级观察）
     *
     * <p><b>调用点</b>：{@code HarnessEngine.handle(...)} 受理判定（第 0 步）<b>通过之后</b>的第一步，
     * 早于 HITL 决策、续流判定、空输入判空与命令分发。被排队（DEFERRED）或拒绝（REJECTED）的输入
     * 不经过本钩子——排队的输入续发时再进 {@code handle}，只触发一次。凡受理的输入都会经过本钩子：
     * 用户消息、HITL 审批、续流、命令触发。需注意：命令若声明了任务意图（{@code AgentTaskIntent}，如 {@code /continue}），
     * {@code CommandDispatcher} 会带着命令产物（{@code commandAware=false}、{@code origin=NONE}）
     * 重新进一次 {@code handle}，因此「命令 + 其产物任务」会触发<b>两次</b>——
     * 实现须能容忍重复（例如幂等处理，或按 {@code input.isCommandAware()} 区分手输与命令产物，
     * 不要用它做唯一分支依据：两者都是合法输入）。</p>
     *
     * <p><b>能力边界</b>：返回 {@code void}——<b>只能观察与就地改写，不能否决</b>。
     * 想做「拦截并改成另一种结局」请走命令分发或代理侧拦截器。
     * {@link HarnessInput} 的模型/代理/技能等选择项有 setter，可在本钩子内补默认值
     * （这是 {@code void} 签名下唯一能改变后续行为的方式）。</p>
     *
     * <p><b>不对称点</b>：{@code task}/{@code multitask} 的委派任务不走 {@code handle}
     * （直接调子代理的 {@code prompt}），因此<b>不会</b>触发本钩子；但它们的请求装配仍会触发
     * {@link #onRequestAssemble}（{@code isDelegated = true}）。若需要「所有执行都埋点」，
     * 应同时钩 {@code onRequestAssemble}。</p>
     *
     * <p><b>注意</b>：判 {@code isEnabled()}。</p>
     *
     * @param engine 引擎
     * @param input  本次输入（可就地改选的模型/代理/技能等）
     * @since 4.2.0
     */
    default void onInput(HarnessEngine engine, HarnessInput input) {

    }

    /**
     * 每次请求装配时（请求级装配）
     *
     * <p><b>调用点</b>：{@code ReActRequest.options(...)} 内，覆盖<b>全部四类请求</b>——
     * 用户新输入（主代理或面板/提及选中的子代理，含 goal 等程序发起的 {@code engine.handle}）、
     * HITL 恢复与 {@code /continue} 等续流、命令触发的任务（此三者 {@code isDelegated = false}）、
     * {@code task}/{@code multitask} 委派的子代理任务（{@code isDelegated = true}）。</p>
     *
     * <p><b>埋点边界</b>：只有经 {@code HarnessEngine.handle(...)} / {@code HarnessEngine.prompt(...)}
     * 发起的请求会被埋点。宿主拿 {@code getMainAgent()} / {@code getAgentBuilder(...).build()}
     * 直接对代理实例调 {@code prompt(...)} 会<b>绕过本钩子</b>（也绕过本引擎的模型/回合数默认值），
     * 需自行补调。</p>
     *
     * <p><b>惰性执行</b>：本钩子注册在 {@code ReActRequest.options(...)} 的回调里，
     * 而回调在请求<b>真正执行</b>时（{@code call()} / {@code stream()} 内的 init）才跑。
     * 因此「构造了请求但未执行」不会触发本钩子；而同一个逻辑任务续跑一次、就会再装配一次。</p>
     *
     * <p><b>与 {@link #onAgentBuild} 的分工</b>：{@code onAgentBuild} 管<b>代理实例</b>的默认值
     * （defaultTalent / defaultInterceptor，影响该代理的每次请求）；
     * 本钩子管<b>这一次请求</b>的选项（retryConfig / maxTurns / interceptorAdd / talentAdd）。
     * 需要「按请求挂载」或「子代理不挂」的组件放这里，放 {@code onAgentBuild} 会作用到所有后续请求。</p>
     *
     * <p><b>注意</b>：判 {@code isEnabled()}；{@code isDelegated = true} 时目标会话是
     * 临时 {@code InMemoryAgentSession}，与前端会话无关，会话级能力（插话、队列）不应挂载。</p>
     *
     * <p><b>重复装配</b>：选项回调用 {@code andThen} 链起（{@code ReActRequest.options} 可多次追加），
     * 且续跑时会从 {@code trace.getOptions().copy()} 继承——即同一请求可能被装配多次。
     * 内置方法本身幂等（{@code interceptorAdd}/{@code talentAdd} 以内含实例的类/名为 Map 键，
     * 重复添加只是覆盖），但<b>实现里自己维护的计数/累加状态会被重复触发</b>，需自行去重。</p>
     *
     * <p><b>与其它钩子的关系</b>：本钩子总在 {@link #onAgentBuild}（代理构建期）之后；
     * 若请求由 {@code handle(...)} 发起，还晚于 {@link #onInput}；
     * 若由 {@code engine.prompt(...)} 直调发起，则<b>不经过</b> {@code onInput}（后者只挂在 {@code handle} 入口）。</p>
     *
     * @param engine        引擎
     * @param agentName     生效代理名（顶层请求取实际目标代理，可能是子代理）
     * @param isDelegated   是否委派执行的子代理任务（{@code task}/{@code multitask}）；
     *                      {@code false} 表示顶层请求（含面板选中的子代理）
     * @param optionsAmend  本次请求的选项修正器
     * @since 4.2.0
     */
    default void onRequestAssemble(HarnessEngine engine, String agentName, boolean isDelegated, ReActOptionsAmend optionsAmend) {

    }

    /**
     * 用户级会话中断（中断参与）
     *
     * <p><b>调用点</b>：{@code HarnessEngine.interrupt(session, InterruptMode.INTERRUPT_ALL)} / {@code prepareInterrupt(session)}
     * （{@code /interrupt} 命令也经此）。仅用户级完整中断会回调；只停流的程序行为
     * （{@code CANCEL_STREAM} / {@code PREEMPT_STREAM}，如新轮次顶掉旧轮次）<b>不</b>回调。</p>
     *
     * <p><b>时序保证</b>：引擎在认领并停止运行流<b>之前</b>回调。停流后宿主常在终态回调里续发积压，
     * 若等到那时才清理，会把刚被停掉的任务重新推起来（「先清积压再停流」）。</p>
     *
     * <p><b>典型用途</b>：积压类插件清空待执行项（queue）。</p>
     *
     * <p><b>注意</b>：不判 {@code isEnabled()}（清理类）；运行在调用线程，<b>不得阻塞</b>；
     * 引擎逐个 try/catch，单个扩展的异常只记告警、不会阻止停流（中断必须总能生效）。</p>
     *
     * @param engine  引擎
     * @param session 被中断的会话（非 null）
     * @since 4.2.0
     */
    default void onSessionInterrupt(HarnessEngine engine, AgentSession session) {

    }

    /**
     * 读取会话状态（事实贡献）
     *
     * <p><b>调用点</b>：{@code HarnessEngine.status(session)}（{@code admit} 受理判定也经此）。
     * 引擎先读定核心事实（运行流 / HITL / 受理窗口），再逐个回调本钩子收集扩展事实。</p>
     *
     * <p><b>典型用途</b>：插件用自己定义的 {@link SessionStatus.Key} 常量贡献自有事实
     * （如积压数 {@code QueueExtension.STATUS_PENDING}、待生效插话数 {@code SteerExtension.STATUS_PENDING}、
     * goal 状态 {@code GoalExtension.STATUS_GOAL}），宿主按键读取，核心无需修改。
     * 这些事实只供展示与宿主业务使用，内核不读。</p>
     *
     * <p><b>注意</b>：不判 {@code isEnabled()}（事实类）；可能在 {@code synchronized (session.attrs())}
     * 内被调用，实现必须<b>廉价、不阻塞、无副作用</b>；若需获取自有锁，持有自有锁期间
     * 不得再取会话锁（锁序固定为「会话锁 → 自有锁」）。引擎逐个 try/catch，单个扩展的异常只记告警。</p>
     *
     * @param engine  引擎
     * @param session 会话（非 null）
     * @param builder 快照组装器（经 {@link SessionStatus.Builder#put} 贡献事实）
     * @since 4.2.0
     */
    default void contributeSessionStatus(HarnessEngine engine, AgentSession session, SessionStatus.Builder builder) {

    }

    /**
     * 会话被永久删除（会话级清理）
     *
     * <p><b>调用点</b>：只由 {@code HarnessEngine.removeSession(sessionId)} 触发，表示<b>永久删除</b>。
     * 只是把会话移出内存、下次还会重建的场景（如回退、关闭连接）不触发。</p>
     *
     * <p><b>时序保证</b>：在宿主移出内存、删除存储<b>之前</b>回调——扩展先撤掉定时任务，
     * 再由宿主删数据，避免定时任务在中间时段触发，把会话重新建出来。</p>
     *
     * <p><b>典型用途</b>：撤掉本扩展为该会话建的定时任务与会话外的持久化状态（loop 任务、goal 调度）。
     * 存在会话属性里的状态随会话一起消失，不必处理。</p>
     *
     * <p><b>注意</b>：只传 {@code sessionId}，实现里<b>不得</b>调用 {@code engine.getSession(sessionId)}
     * （会把会话重新建出来）；不判 {@code isEnabled()}（清理类）；实现须<b>幂等</b>
     * （宿主可能重试，或对不存在的会话调用）且<b>不得阻塞</b>；
     * 引擎逐个 try/catch，单个扩展的异常只记告警。</p>
     *
     * @param engine    引擎
     * @param sessionId 被删除的会话标识（非空）
     * @since 4.2.0
     */
    default void onSessionRemove(HarnessEngine engine, String sessionId) {

    }

    /**
     * 是否没有需要本引擎继续存活的后台工作（事实收集）
     *
     * <p><b>调用点</b>：{@code HarnessEngine.isQuiescent()}——宿主按空闲回收引擎前询问（如 host 模块的引擎池）。
     * 返回 false 的典型情形：还有活跃的 goal、已登记的定时任务。</p>
     *
     * <p><b>注意</b>：不判 {@code isEnabled()}（事实类：停用前建的定时任务仍然存在）；
     * 必须廉价、无副作用；异常按「不静默」处理。</p>
     *
     * @param engine 引擎
     * @return 缺省 true
     * @since 4.2.0
     */
    default boolean isQuiescent(HarnessEngine engine) {
        return true;
    }

    /**
     * 本扩展给模型挂的工具名（声明）
     *
     * <p><b>调用点</b>：作用域守卫。已声明的工具不受 {@code InputScope.tools} 名单约束（跟随扩展是否挂载），
     * 否则 goal 会因名单里没写 {@code goal_update} 而永远无法完成；未声明的非内置、非 MCP 工具
     * （如宿主自注册的工具）与内置工具同口径，必须在名单内。</p>
     *
     * <p>必须廉价、无副作用；建议直接取自己 Talent 的工具表，避免手写名字与实际工具漂移。</p>
     *
     * @return 缺省空
     * @since 4.2.0
     */
    default Collection<String> getToolNames() {
        return Collections.emptyList();
    }
}
