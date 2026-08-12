package com.diet.service.clarify;

import com.diet.agent.factory.AgentFactory;
import com.diet.model.ClarifyResult;
import com.diet.model.SlotBundle;
import com.diet.service.trace.AgentTraceService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * ClarifyAgent 调用服务。
 * 规则层（ClarifyRuleService）先判 ASK/READY；仅 ASK 时才调 LLM 生成自然语言追问。
 */
@Service
public class ClarifyAgentService {

    /** 按 sessionId 提供 ClarifyAgent 实例的工厂。 */
    private final AgentFactory agentFactory;

    /** 澄清规则服务，计算 missingSlots 决定是否需要追问。 */
    private final ClarifyRuleService clarifyRuleService;

    /** 链路追踪服务，callAgent 内部记录 AGENT_CALL 事件。 */
    private final AgentTraceService agentTraceService;

    /** ClarifyAgent 使用的轻量模型名，来自配置 diet.llm.light-model。 */
    private final String modelName;

    /** 构造器注入依赖。 */
    public ClarifyAgentService(
            AgentFactory agentFactory,
            ClarifyRuleService clarifyRuleService,
            AgentTraceService agentTraceService,
            @Value("${diet.llm.light-model:qwen-turbo}") String modelName
    ) {
        this.agentFactory = agentFactory;
        this.clarifyRuleService = clarifyRuleService;
        this.agentTraceService = agentTraceService;
        this.modelName = modelName;
    }

    /**
     * 根据槽位决定是否追问，需要时生成追问文案。
     * 由 Orchestrator#handleRecommendation 调用，返回 ClarifyResult（ASK 或 READY）。
     */
    public ClarifyResult decide(String sessionId, String userInput, SlotBundle slots) {
        // A-规则判断缺失槽位：检查 mealTime 是否为空、healthGoal 是否可缺省
        List<String> missingSlots = clarifyRuleService.missingSlots(slots);

        // B-1 槽位足够：不调 LLM，直接返回 READY
        if (missingSlots.isEmpty()) {
            return ClarifyResult.ready();
        }
        try {
            // B-2 槽位不够 → 调 LLM 生成自然语言追问文案
            ReActAgent agent = agentFactory.get(sessionId).clarify();
            agent.getMemory().clear();
            Msg response = agentTraceService.callAgent(sessionId, "ClarifyAgent", modelName, agent, buildUserPrompt(userInput, slots, missingSlots));
            String question = response.getTextContent() == null ? "" : response.getTextContent().trim();

            // C-兜底：LLM 返回空文本时，用模板追问代替
            if (question.isBlank()) {
                question = clarifyRuleService.fallbackQuestion(missingSlots);
            }
            return ClarifyResult.ask(question, missingSlots);
        } catch (Exception ignored) {
            // D-异常兜底：LLM 超时/报错时，模板追问保证用户一定能看到问题
            return ClarifyResult.ask(clarifyRuleService.fallbackQuestion(missingSlots), missingSlots);
        }
    }

    /** 构造 ClarifyAgent 的输入 prompt。 */
    private String buildUserPrompt(String userInput, SlotBundle slots, List<String> missingSlots) {
        return """
                用户原话：%s
                已知信息：%s
                缺失字段：%s
                """.formatted(userInput, slots, missingSlots);
    }
}