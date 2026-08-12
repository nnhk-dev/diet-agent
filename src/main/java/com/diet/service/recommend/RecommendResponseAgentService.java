package com.diet.service.recommend;

import com.diet.agent.factory.AgentFactory;
import com.diet.enums.SourceMode;
import com.diet.model.MealItem;
import com.diet.model.MealResponse;
import com.diet.model.RecommendResult;
import com.diet.model.RecommendedMealOption;
import com.diet.model.ResponseResult;
import com.diet.model.SlotBundle;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RecommendResponseAgent 服务（Orchestrator 推荐流水线第三层）。
 * 一次 LLM 调用同时生成 top3 推荐理由和面向用户的口语 speechText。
 */
@Service
public class RecommendResponseAgentService {

    /**
     * 按 sessionId 提供 RecommendResponseAgent 实例的工厂。
     */
    private final AgentFactory agentFactory;

    /**
     * 从 LLM 输出文本中提取 JSON 对象的工具。
     */
    private final LlmJsonService llmJsonService;

    /**
     * 链路追踪服务，callAgent 内部记录 AGENT_CALL 事件。
     */
    private final AgentTraceService agentTraceService;

    /**
     * RecommendResponseAgent 使用的主模型名，来自配置 diet.llm.main-model。
     */
    private final String modelName;

    /**
     * 构造器注入依赖。
     */
    public RecommendResponseAgentService(
            AgentFactory agentFactory,
            LlmJsonService llmJsonService,
            AgentTraceService agentTraceService,
            @Value("${diet.llm.main-model:qwen-max}") String modelName
    ) {
        this.agentFactory = agentFactory;
        this.llmJsonService = llmJsonService;
        this.agentTraceService = agentTraceService;
        this.modelName = modelName;
    }

    /**
     * 合并推荐链路输出：RecommendResult + ResponseResult。
     * 由 Orchestrator#completeRecommendation 在 MEAL_RANKED 之后调用。
     */
    public Result recommendAndRespond(
            String sessionId,
            String userInput,
            SourceMode sourceMode,
            SlotBundle slots,
            List<MealItem> rankedMeals) {
        // A-只取 top3 给 LLM：Rank 已排好序，3 道够选，省 token
        List<MealItem> topMeals = rankedMeals == null ? List.of() : rankedMeals.stream().limit(3).toList();

        // 候选为空时直接返回空推荐，不调 LLM
        if (topMeals.isEmpty()) {
            RecommendResult empty = RecommendResult.empty();
            return new Result(empty, ResponseResult.textOnly("暂时没有找到很匹配的餐食，你可以先补充对应特征的餐食"));
        }

        // B-检查是否需要健康免责声明（减脂/低糖/控碳水/养胃）
        boolean needDisclaimer = needsDisclaimer(slots);
        try {
            // C-调 LLM（qwen-max）：system prompt=recommend-response.txt，user prompt=用户原话+槽位+top3
            ReActAgent agent = agentFactory.get(sessionId).recommendResponse();
            agent.getMemory().clear();
            Msg response = agentTraceService.callAgent(
                    sessionId,
                    "RecommendResponseAgent",
                    modelName,
                    agent,
                    buildUserPrompt(userInput, sourceMode, slots, topMeals)
            );
            // D-解析 JSON → recommendations（理由数组）+ speechText（口语回复）
            ParsedOutput parsed = parseOutput(response.getTextContent(), topMeals, slots);//parseOutput
            RecommendResult recommend = new RecommendResult(parsed.options(), needDisclaimer);
            ResponseResult responseResult = new ResponseResult(parsed.speechText(), toDisplayBlocks(recommend), "WAIT_USER");
            return new Result(recommend, responseResult);

        } catch (Exception ignored) {//防幻-D：全模板兜底
            // E-异常兜底：LLM 超时/报错时，模板理由+模板文案保证用户一定看到回复
            RecommendResult recommend = new RecommendResult(templateOptions(topMeals, slots), needDisclaimer);
            return new Result(recommend, new ResponseResult(templateSpeech(recommend), toDisplayBlocks(recommend), "WAIT_USER"));
        }
    }

    /**
     * 构造 RecommendResponseAgent 的输入 prompt。
     */
    private String buildUserPrompt(String userInput, SourceMode sourceMode, SlotBundle slots, List<MealItem> topMeals) {
        return """
                用户原话：%s
                数据源模式：%s
                本轮槽位：%s
                候选餐食：%s
                请输出 JSON，包含 recommendations 数组（每项 mealId + reason）和 speechText，不要编造候选之外的餐食。
                """.formatted(userInput, sourceMode, slots, topMeals);
    }//防幻-A：buildUserPrompt()：用户原话+槽位+top3，确保 LLM 输出只包含候选餐食

    /**
     * 解析 Agent 返回的 JSON 为 ParsedOutput。
     */
    private ParsedOutput parseOutput(String content, List<MealItem> topMeals, SlotBundle slots) {
        JsonNode root = llmJsonService.parseObject(content);// 提取 JSON 根节点

        //防幻-B：parseOptions()：LLM 输出的每个 mealId 都必须出现在 byId（topMeals 白名单）里，否则丢弃
        List<RecommendedMealOption> options = parseOptions(root.path("recommendations"), topMeals, slots); // 解析推荐数组
        String speechText = root.path("speechText").asText("").trim();                    // 读取口语回复
        if (speechText.isBlank()) {
            speechText = templateSpeech(new RecommendResult(options, needsDisclaimer(slots))); // 空则用模板
        }
        return new ParsedOutput(options, speechText);
    }

    /**
     * 解析 recommendations JSON 数组，只保留 topMeals 中存在的 mealId。
     */
    private List<RecommendedMealOption> parseOptions(JsonNode recommendationsNode, List<MealItem> topMeals, SlotBundle slots) {
        Map<Long, MealItem> byId = new LinkedHashMap<>();
        topMeals.forEach(meal -> byId.put(meal.id(), meal));           // mealId → MealItem 索引
        Map<Long, String> reasons = new LinkedHashMap<>();
        if (recommendationsNode.isArray()) {
            recommendationsNode.forEach(node -> {
                // 兼容 mealId 和 itemId 两种字段名
                long mealId = node.path("mealId").isMissingNode() ? node.path("itemId").asLong() : node.path("mealId").asLong();
                String reason = node.path("reason").asText("");
                // 只采纳候选内且 reason 非空的项
                if (byId.containsKey(mealId) && !reason.isBlank()) {
                    reasons.put(mealId, reason);
                }
            });
        }
        List<RecommendedMealOption> result = new ArrayList<>();
        //防幻-C：按 topMeals 顺序输出，缺失 reason 时用 templateReason 兜底
        for (MealItem meal : topMeals) {
            result.add(toOption(meal, reasons.getOrDefault(meal.id(), templateReason(meal, slots))));
        }
        return result;
    }

    /**
     * LLM 失败时为 topMeals 生成模板推荐理由列表。
     */
    private List<RecommendedMealOption> templateOptions(List<MealItem> topMeals, SlotBundle slots) {
        return topMeals.stream()
                .map(meal -> toOption(meal, templateReason(meal, slots)))
                .toList();
    }

    /**
     * MealItem + reason 转为 RecommendedMealOption。
     */
    private RecommendedMealOption toOption(MealItem meal, String reason) {
        return new RecommendedMealOption(meal.id(), meal.sourceType(), meal.name(), reason, meal.matchScore(), meal.slots());
    }

    /**
     * 根据 slots 生成单条模板推荐理由。
     */
    private String templateReason(MealItem meal, SlotBundle slots) {
        if (slots != null && !slots.healthGoal().isEmpty()) {
            return meal.name() + "比较符合你提到的" + String.join("、", slots.healthGoal()) + "诉求。";
        }
        if (slots != null && !slots.taste().isEmpty()) {
            return meal.name() + "比较贴近你想要的" + String.join("、", slots.taste()) + "口味。";
        }
        return meal.name() + "和你这轮表达的就餐偏好匹配度较高。";
    }

    /**
     * 将 RecommendResult 转为前端展示用的 MealResponse 卡片列表。
     */
    private List<MealResponse> toDisplayBlocks(RecommendResult recommendResult) {
        if (recommendResult == null || recommendResult.recommendations() == null) {
            return List.of();
        }
        return recommendResult.recommendations().stream()
                .map(this::toMealResponse)
                .toList();
    }

    /**
     * RecommendedMealOption 转为 MealResponse（含各维 slots 标签）。
     */
    private MealResponse toMealResponse(RecommendedMealOption option) {
        return new MealResponse(
                option.itemId(),
                option.sourceType(),
                option.name(),
                option.matchedSlots().mealTime(),
                option.matchedSlots().mood(),
                option.matchedSlots().scene(),
                option.matchedSlots().healthGoal(),
                option.matchedSlots().cuisine(),
                option.matchedSlots().taste(),
                option.matchedSlots().convenience(),
                option.matchScore()
        );
    }

    /**
     * LLM 失败时的模板口语回复，含可选免责声明。
     */
    private String templateSpeech(RecommendResult recommendResult) {
        if (recommendResult == null || recommendResult.recommendations().isEmpty()) {
            return "暂时没有找到很匹配的餐食，你可以补充餐次、口味或想要清淡/顶饱这类目标。";
        }
        StringBuilder builder = new StringBuilder("我优先给你推荐这几款：");
        for (RecommendedMealOption option : recommendResult.recommendations()) {
            builder.append("\n- ").append(option.name()).append("：").append(option.reason());
        }
        if (recommendResult.needDisclaimer()) {
            builder.append("\n这些建议只做日常饮食参考，如果有明确疾病或特殊身体情况，建议咨询医生或营养师。");
        }
        return builder.toString();
    }

    /**
     * 健康相关槽位命中时需附加免责声明。
     */
    private boolean needsDisclaimer(SlotBundle slots) {
        return slots != null && slots.healthGoal().stream().anyMatch(value ->
                value.contains("减脂") || value.contains("低糖") || value.contains("控碳水") || value.contains("养胃"));
    }

    /**
     * recommendAndRespond 的返回结构：RecommendResult + ResponseResult。
     */
    public record Result(RecommendResult recommend, ResponseResult response) {
    }

    /**
     * parseOutput 的中间结构。
     */
    private record ParsedOutput(List<RecommendedMealOption> options, String speechText) {
    }
}