package com.diet.service.orchestrator;

import com.diet.exception.DietException;
import com.diet.service.intent.IntentAgentService;
import com.diet.service.intent.IntentReviseService;
import com.diet.service.risk.RiskGuardService;
import com.diet.enums.ClarifyAction;
import com.diet.model.ClarifyResult;
import com.diet.model.RiskGuardResult;
import com.diet.enums.Intent;
import com.diet.model.IntentResult;
import com.diet.model.MealItem;
import com.diet.model.MealRankRequest;
import com.diet.model.MealSearchRequest;
import com.diet.model.ChatRequest;
import com.diet.model.ChatResponse;
import com.diet.model.RecommendResult;
import com.diet.model.ResponseResult;
import com.diet.enums.SessionPhase;
import com.diet.model.SessionState;
import com.diet.model.SlotBundle;
import com.diet.enums.SourceMode;
import com.diet.service.clarify.ClarifyAgentService;
import com.diet.service.meal.MealRankService;
import com.diet.service.meal.MealSearchService;
import com.diet.service.meal.MealService;
import com.diet.service.plan.MealPlanService;
import com.diet.service.plan.PlanResponseAgentService;
import com.diet.service.recommend.RecommendResponseAgentService;
import com.diet.service.session.SessionService;
import com.diet.service.session.SessionStateService;
import com.diet.service.slot.SlotMergeService;
import com.diet.service.trace.AgentTraceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 饮食推荐多 Agent 编排服务（Orchestrator）。
 * <p>
 * 一轮 {@link #dietChat} 的主链路：加载会话 → Trace → 加锁 → 记录消息 → 意图识别 → 路由 → 推荐/澄清/固定回复 → 落库。
 */
@Service
public class DietOrchestratorService {

    /**
     * 闲聊时的固定引导文案，不额外调用 LLM。
     */
    private static final String CHITCHAT_REPLY = "我主要负责帮你决定吃什么。你可以告诉我餐次、口味，或者想清淡点还是顶饱点。";

    /**
     * 会话消息落库服务，写入 diet_messages 表。
     */
    private final SessionService sessionService;

    /**
     * 会话状态服务，读写 phase、slots、lastRecommendations 等到 diet_sessions 表。
     */
    private final SessionStateService sessionStateService;

    /**
     * 意图识别 Agent 服务，调用 LLM 识别 intent + slots。
     */
    private final IntentAgentService intentAgentService;

    /**
     * 意图矫正规则服务，用历史状态二次修正 LLM 输出。
     */
    private final IntentReviseService intentReviseService;

    /**
     * 槽位合并服务，多轮对话中合并历史槽位与本轮槽位。
     */
    private final SlotMergeService slotMergeService;

    /**
     * 澄清 Agent 服务，槽位不足时生成追问文案。
     */
    private final ClarifyAgentService clarifyAgentService;

    /**
     * 餐食检索服务，按 sourceMode + slots 从 DB 召回候选。
     */
    private final MealSearchService mealSearchService;

    /**
     * 餐食重排服务，对候选按槽位命中二次打分排序。
     */
    private final MealRankService mealRankService;

    /**
     * 推荐应答 Agent 服务，一次 LLM 调用生成推荐理由 + 口语回复。
     */
    private final RecommendResponseAgentService recommendResponseAgentService;

    /**
     * 多餐规划服务：解析餐次并按餐次拆分检索重排。
     */
    private final MealPlanService mealPlanService;

    /**
     * 多餐规划应答 Agent：按餐次生成理由与口语回复。
     */
    private final PlanResponseAgentService planResponseAgentService;

    /**
     * 餐食服务，用于 PERSONAL 模式空库前置检查。
     */
    private final MealService mealService;

    /**
     * 健康风险守卫，拦截医疗承诺/极端节食等高风险表述。
     */
    private final RiskGuardService riskGuardService;

    /**
     * 链路追踪服务，记录状态机事件和 Agent 调用到 agent_traces 表。
     */
    private final AgentTraceService agentTraceService;

    /**
     * 会话级锁 Map，key=sessionId，value=锁对象，保证同 session 串行写状态。
     */
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    /**
     * Spring 构造器注入全部依赖。
     */
    public DietOrchestratorService(
            SessionService sessionService,
            SessionStateService sessionStateService,
            IntentAgentService intentAgentService,
            IntentReviseService intentReviseService,
            SlotMergeService slotMergeService,
            ClarifyAgentService clarifyAgentService,
            MealSearchService mealSearchService,
            MealRankService mealRankService,
            RecommendResponseAgentService recommendResponseAgentService,
            MealPlanService mealPlanService,
            PlanResponseAgentService planResponseAgentService,
            MealService mealService,
            RiskGuardService riskGuardService,
            AgentTraceService agentTraceService
    ) {
        this.sessionService = sessionService;                           // 注入消息落库服务
        this.sessionStateService = sessionStateService;                 // 注入会话状态服务
        this.intentAgentService = intentAgentService;                   // 注入意图识别服务
        this.intentReviseService = intentReviseService;                 // 注入意图矫正服务
        this.slotMergeService = slotMergeService;                       // 注入槽位合并服务
        this.clarifyAgentService = clarifyAgentService;                 // 注入澄清 Agent 服务
        this.mealSearchService = mealSearchService;                     // 注入餐食检索服务
        this.mealRankService = mealRankService;                         // 注入餐食重排服务
        this.recommendResponseAgentService = recommendResponseAgentService; // 注入推荐应答 Agent 服务
        this.mealPlanService = mealPlanService;                         // 注入多餐规划服务
        this.planResponseAgentService = planResponseAgentService;       // 注入规划应答 Agent 服务
        this.mealService = mealService;                                 // 注入餐食服务
        this.riskGuardService = riskGuardService;             // 注入健康守卫
        this.agentTraceService = agentTraceService;                     // 注入链路追踪服务
    }

    /**
     * 同步处理一轮用户输入并返回完整推荐结果（HTTP 入口对应方法）。
     */
    public ChatResponse dietChat(Long userId, ChatRequest request) {
        // A-生成 traceId：给本轮请求发唯一工单号，贯穿后续所有 Trace 事件
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");

        // B-校验参数：message 不能为空，sourceMode 必填，不合法直接挡掉
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new DietException("用户问题不能为空");
        }
        if (request.sourceMode() == null) {
            throw new DietException("sourceMode 不能为空，请选择 PERSONAL 或 PUBLIC");
        }

        // C-加载/创建会话：从 DB 查已有状态，新用户则初始化，得到 sessionId + slots + phase
        SessionState initialState = sessionStateService.loadOrCreate(request.sessionId(), userId, request.sourceMode());

        // D-开 Trace + 拿会话锁：Trace 上下文记录全链路事件；锁保证同一 session 并发请求串行
        try (AgentTraceService.TraceScope ignored = agentTraceService.openTrace(traceId, initialState.sessionId(), userId)) {
            try {
                long startedAt = System.nanoTime();
                agentTraceService.recordEvent("REQUEST_RECEIVED", "HTTP", request, initialState);

                Object lock = sessionLocks.computeIfAbsent(initialState.sessionId(), key -> new Object());
                synchronized (lock) {
                    // 调用 handleTurn：正式开始派活（记消息 → 识意图 → 路由 → 推荐/追问）
                    ChatResponse response = handleTurn(userId, request, traceId, initialState);
                    agentTraceService.recordEvent("REQUEST_FINISHED", "HTTP", request, response, elapsedMs(startedAt));
                    return response;
                }
            } catch (RuntimeException error) {
                agentTraceService.recordError("REQUEST_FAILED", "HTTP", request, error);
                throw error;
            }
        }
    }

    /**
     * 在会话锁内执行完整状态机：记消息 → 前置校验 → 意图识别 → 路由分发。
     */
    private ChatResponse handleTurn(Long userId, ChatRequest request, String traceId, SessionState state) {
        //A-记录用户消息：将用户消息 INSERT 到 diet_messages 表，role=user，intent=null，关联 traceId

        // 从会话状态中取出 sessionId，后续落库和 Agent 调用都依赖它
        String sessionId = state.sessionId();
        // 从会话状态中取出数据源模式（PERSONAL / PUBLIC）
        SourceMode sourceMode = state.sourceMode();
        // 将用户消息 INSERT 到 diet_messages 表，role=user，intent=null，关联 traceId
        sessionService.appendMessage(sessionId, "user", request.message(), null, traceId);

        // Trace 事件：USER_MESSAGE_RECORDED | 阶段 SESSION | 输入=用户原文 | 输出=sessionId+sourceMode
        agentTraceService.recordEvent("USER_MESSAGE_RECORDED", "SESSION", request.message(), Map.of("sessionId", sessionId, "sourceMode", sourceMode));

        // PERSONAL 模式且用户尚未录入任何个人餐食时，提前返回引导文案，跳过后续检索｜
        if (sourceMode == SourceMode.PERSONAL && !mealService.hasPersonalMeals(userId)) {
            // 构造纯文本响应，提示用户先录入菜单
            ResponseResult response = ResponseResult.textOnly("你还没有录入个人餐食数据。可以先添加几道常吃的食堂菜，再让我按你的饭堂菜单推荐。");
            // Trace 事件：PERSONAL_LIBRARY_EMPTY | 阶段 ROUTE | 输入=userId | 输出=引导文案
            agentTraceService.recordEvent("PERSONAL_LIBRARY_EMPTY", "ROUTE", Map.of("userId", userId), response);
            // 走纯文本完成分支：保存状态 + 落库助手消息 + 返回 ChatResponse
            return completeTextOnly(sessionId, traceId, state, Intent.MEAL_RECOMMENDATION, response);
        }

        // C-意图识别：调用 IntentAgent：传入 sessionId、userId、用户原文、历史槽位、最近 3 条对话摘要

        IntentResult rawIntent = intentAgentService.recognize(sessionId, userId, request.message(), state.slots(), sessionService.recentConversationTurns(sessionId, userId, 3));
        // Trace 事件：INTENT_RECOGNIZED | 阶段 INTENT | 输入=用户原文 | 输出=IntentResult（intent/slots/confidence）
        agentTraceService.recordEvent("INTENT_RECOGNIZED", "INTENT", request.message(), rawIntent);

        //D-意图矫正：调用 IntentReviseService，结合历史 phase/slots/lastRecommendations 二次矫正意图

        IntentResult intent = intentReviseService.revise(state, rawIntent, request.message());
        // Trace 事件：INTENT_REVISED | 阶段 INTENT | 输入=矫正前 rawIntent | 输出=矫正后 intent
        agentTraceService.recordEvent("INTENT_REVISED", "INTENT", rawIntent, intent);

        // Trace 事件：ROUTE_SELECTED | 阶段 ROUTE | 输入=最终 intent | 输出=路由目标 intent 枚举名
        agentTraceService.recordEvent("ROUTE_SELECTED", "ROUTE", intent, Map.of("route", intent.intent()));

        // 按最终意图枚举分发到对应分支处理器
        //E-路由分发：AI 判断出用户想干嘛 → 然后走不同的分支

        return switch (intent.intent()) {
            // 推荐或需澄清：走推荐主链路（澄清由 ClarifyAgent 内部决定）
            case MEAL_RECOMMENDATION, CLARIFY_NEEDED ->
                    handleRecommendation(sessionId, userId, request.message(), traceId, state, intent);
            // 调整上轮推荐：排除已推荐 ID，重跑推荐流水线
            case MEAL_ADJUST -> handleAdjust(sessionId, userId, request.message(), traceId, state, intent);
            // 多餐规划：按餐次拆分检索后统一包装
            case MEAL_PLAN -> handlePlan(sessionId, userId, request.message(), traceId, state, intent);
            // 健康风险：返回 NutritionGuard 保守提示，不走推荐
            case HEALTH_RISK -> handleHealthRisk(sessionId, traceId, state);
            // 其他无关饮食的内容：返回固定引导文案
            case OTHER -> handleChitchat(sessionId, traceId, state);
        };
    }

    /**
     * 推荐主链路：合并槽位 → ClarifyAgent 判追问 → 槽位足够则进入 completeRecommendation。
     */
    private ChatResponse handleRecommendation(String sessionId, Long userId, String userInput, String traceId, SessionState state, IntentResult intent) {
        // A-合并槽位：将历史 slots 与 IntentAgent 本轮识别的 slots 合并（本轮非空覆盖，本轮空保留历史）
        SlotBundle mergedSlots = slotMergeService.merge(state.slots(), intent.slots());

        // Trace 事件：SLOTS_MERGED | 阶段 SLOT | 输入=stateSlots+intentSlots | 输出=mergedSlots
        agentTraceService.recordEvent("SLOTS_MERGED", "SLOT", Map.of("stateSlots", state.slots(), "intentSlots", intent.slots()), mergedSlots);

        // 基于合并槽位构建工作态：意图固定为 MEAL_RECOMMENDATION
        SessionState workingState = state.withIntent(Intent.MEAL_RECOMMENDATION).withSlots(mergedSlots);

        // B-判断槽位是否足够：
        // 【重要】不能完全依靠agent的意图识别,在进入推荐之前,规则层面上也需要判断是否有足够的信息
        // 调用 ClarifyAgent：规则层先判缺失槽位，不足则 LLM 生成追问文案
        ClarifyResult clarify = clarifyAgentService.decide(sessionId, userInput, mergedSlots);
        // Trace 事件：CLARIFY_DECISION | 阶段 CLARIFY | 输入=mergedSlots | 输出=ClarifyResult（ASK/READY）
        agentTraceService.recordEvent("CLARIFY_DECISION", "CLARIFY", mergedSlots, clarify);

        // C-1追问槽位：若 ClarifyResult.action == ASK，说明槽位不足，需要追问用户
        if (clarify.action() == ClarifyAction.ASK) {
            // 直接返回追问，不进入检索推荐—————>澄清链路（ASK）：更新会话状态 → 记录追问消息 → 返回追问响应。
            return completeAsk(sessionId, traceId, workingState, clarify);
        }
        // C-2槽位足够：phase 切 RECOMMEND，excludeMealIds 为空，进入推荐流程
        return completeRecommendation(sessionId, userId, userInput, traceId, workingState.withPhase(SessionPhase.RECOMMEND), List.of());
    }

    /**
     * 澄清链路（ASK）：更新会话状态 → 记录追问消息 → 返回追问响应。
     */
    private ChatResponse completeAsk(String sessionId, String traceId, SessionState workingState, ClarifyResult clarify) {
        // A-更新会话状态：phase 切为 CLARIFY，标记当前在等用户回复
        SessionState clarifyState = workingState.withPhase(SessionPhase.CLARIFY);
        sessionStateService.save(clarifyState);

        // B-记录追问消息：助手生成的追问文案写入 diet_messages，intent=CLARIFY_NEEDED
        sessionService.appendMessage(sessionId, "assistant", clarify.questionToAsk(), Intent.CLARIFY_NEEDED.name(), traceId);

        // C-构造 Clarify 响应：responseType=CLARIFY，携带追问文案+缺失槽位列表，前端据此展示追问气泡和标签 chips
        ChatResponse response = ChatResponse.clarify(sessionId, traceId, clarify.questionToAsk(), clarify.missingSlots());

        agentTraceService.recordEvent("RESPONSE_READY", "CLARIFY", clarify, response);

        // 返回追问，不进入检索推荐
        return response;
    }

    /**
     * 调整链路：合并槽位 → 取 excludeMealIds → 重跑推荐流水线。
     */
    private ChatResponse handleAdjust(String sessionId, Long userId, String userInput, String traceId, SessionState state, IntentResult intent) {
        // A-合并槽位：用户说"清淡点"时，本轮新槽位覆盖历史，其他维度保留
        SlotBundle mergedSlots = slotMergeService.merge(state.slots(), intent.slots());

        // B-取排除列表：拿出本会话已推荐过的 mealId，"换一批"时这些不再出现
        List<Long> excludeMealIds = state.lastRecommendations() == null ? List.of() : state.lastRecommendations();

        agentTraceService.recordEvent("ADJUST_CONTEXT_RESOLVED", "ADJUST", intent, traceMap("mergedSlots", mergedSlots, "excludeMealIds", excludeMealIds));

        // C-复用推荐流水线：意图标为 MEAL_ADJUST，phase 切 RECOMMEND，带 excludeMealIds 进 completeRecommendation
        SessionState workingState = state.withIntent(Intent.MEAL_ADJUST)
                .withSlots(mergedSlots)
                .withPhase(SessionPhase.RECOMMEND);

        return completeRecommendation(sessionId, userId, userInput, traceId, workingState, excludeMealIds);
    }

    /**
     * 多餐规划链路：合并槽位 → 解析餐次 → 按餐次拆分检索重排 → 规划应答包装。
     */
    private ChatResponse handlePlan(String sessionId, Long userId, String userInput, String traceId, SessionState state, IntentResult intent) {
        // 合并历史槽位与本轮槽位（共享口味/健康诉求等；mealTime 会在拆分时按餐次覆盖）
        SlotBundle mergedSlots = slotMergeService.merge(state.slots(), intent.slots());
        List<String> planMealTimes = mealPlanService.resolveMealTimes(mergedSlots);
        // 规划态 slots 显式写入目标餐次，便于后续轮次与 Trace 观察
        SlotBundle planSlots = new SlotBundle(
                planMealTimes,
                mergedSlots.mood(),
                mergedSlots.scene(),
                mergedSlots.healthGoal(),
                mergedSlots.cuisine(),
                mergedSlots.taste(),
                mergedSlots.convenience()
        );
        // Trace 事件：PLAN_CONTEXT_RESOLVED | 阶段 PLAN | 输入=intent | 输出=planSlots+mealTimes
        agentTraceService.recordEvent(
                "PLAN_CONTEXT_RESOLVED",
                "PLAN",
                intent,
                traceMap("mergedSlots", mergedSlots, "planMealTimes", planMealTimes, "planSlots", planSlots)
        );

        SessionState workingState = state.withIntent(Intent.MEAL_PLAN).withSlots(planSlots).withPhase(SessionPhase.PLAN);
        return completePlan(sessionId, userId, userInput, traceId, workingState, planMealTimes);
    }

    /**
     * 多餐规划流水线：按餐次 search/rank 各取一款 → PlanResponseAgent → Guard → 落库。
     */
    private ChatResponse completePlan(String sessionId,
                                      Long userId,
                                      String userInput,
                                      String traceId,
                                      SessionState state,
                                      List<String> planMealTimes) {
        List<MealPlanService.PlannedMeal> plannedMeals = mealPlanService.planMeals(
                state.sourceMode(), userId, state.slots(), planMealTimes);

        List<Map<String, Object>> planTrace = new ArrayList<>();
        for (MealPlanService.PlannedMeal planned : plannedMeals) {
            planTrace.add(traceMap(
                    "mealTime", planned.mealTime(),
                    "matched", planned.matched(),
                    "mealId", planned.matched() ? planned.meal().id() : null,
                    "mealName", planned.matched() ? planned.meal().name() : null
            ));
        }
        agentTraceService.recordEvent(
                "MEAL_PLAN_SEARCHED",
                "PLAN",
                Map.of("planMealTimes", planMealTimes, "slots", state.slots()),
                Map.of("plannedCount", plannedMeals.size(), "plannedMeals", planTrace)
        );

        boolean anyMatched = plannedMeals.stream().anyMatch(MealPlanService.PlannedMeal::matched);
        if (!anyMatched) {
            ResponseResult empty = ResponseResult.textOnly(state.sourceMode() == SourceMode.PERSONAL
                    ? "你当前的个人餐食库里暂时拼不出多餐方案，可以补充更多饭堂菜，或者切换到公共餐食数据试试。"
                    : "公共餐食库里暂时拼不出完整的多餐方案，你可以补充口味、菜系，或换个人模式再试。");
            agentTraceService.recordEvent("NO_MEAL_PLAN_MATCHED", "PLAN", state, empty);
            return completeTextOnly(sessionId, traceId, state, Intent.MEAL_PLAN, empty);
        }

        RecommendResponseAgentService.Result merged = planResponseAgentService.planAndRespond(
                sessionId, userInput, state.sourceMode(), state.slots(), plannedMeals);

        RecommendResult recommend = merged.recommend();
        agentTraceService.recordEvent(
                "PLAN_RESULT_BUILT",
                "PLAN",
                Map.of("strategy", Intent.MEAL_PLAN.name(), "plannedMeals", planTrace),
                recommend
        );

        ResponseResult response = merged.response();
        agentTraceService.recordEvent("PLAN_RESPONSE_AGENT_RESULT", "RESPONSE", recommend, response);

        RiskGuardResult guard = riskGuardService.check(userInput, Intent.MEAL_PLAN, recommend, response);
        agentTraceService.recordEvent(
                "NUTRITION_GUARD_CHECKED",
                "GUARD",
                Map.of("intent", Intent.MEAL_PLAN, "response", response),
                guard
        );

        if (!guard.passed()) {
            response = ResponseResult.textOnly(guard.rewriteSuggestion());
            agentTraceService.recordEvent("NUTRITION_GUARD_REWRITTEN", "GUARD", guard, response);
        } else {
            agentTraceService.recordEvent("COMPLIANCE_GUARD_REWRITTEN", "GUARD", null, response);
        }

        List<Long> lastIds = recommend.recommendations().stream().map(option -> option.itemId()).toList();
        SessionState savedState = state.appendLastRecommendations(lastIds);
        sessionStateService.save(savedState);
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), Intent.MEAL_PLAN.name(), traceId);

        ChatResponse chatResponse = ChatResponse.answer(
                sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction());
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", savedState, chatResponse);
        return chatResponse;
    }

    /**
     * 健康风险分支：返回 NutritionGuard 保守提示，不走推荐链路。
     */
    private ChatResponse handleHealthRisk(String sessionId, String traceId, SessionState state) {
        // 构造纯文本响应，内容为 conservativeMessage 固定文案
        ResponseResult response = ResponseResult.textOnly(riskGuardService.conservativeMessage());
        // 走纯文本完成分支，intent 标记为 HEALTH_RISK
        return completeTextOnly(sessionId, traceId, state, Intent.HEALTH_RISK, response);
    }

    /**
     * 闲聊分支：返回固定引导文案，不调用 LLM。
     */
    private ChatResponse handleChitchat(String sessionId, String traceId, SessionState state) {
        // 构造纯文本响应，内容为 CHITCHAT_REPLY 常量
        ResponseResult response = ResponseResult.textOnly(CHITCHAT_REPLY);
        // 走纯文本完成分支，intent 标记为 CHITCHAT
        return completeTextOnly(sessionId, traceId, state, Intent.OTHER, response);
    }

    /**
     * 完整推荐流水线：检索 → 重排 → LLM 生成理由与口语回复 → Guard 审查 → 持久化并返回。
     */
    private ChatResponse completeRecommendation(String sessionId, Long userId, String userInput, String traceId, SessionState state, List<Long> excludeMealIds) {
        // A-粗召回：MySQL JSON_OVERLAPS 按槽位匹配，最多 50 条
        List<MealItem> candidates = mealSearchService.search(new MealSearchRequest(state.sourceMode(), userId, state.slots(), excludeMealIds));
        agentTraceService.recordEvent("MEAL_SEARCHED", "SEARCH", state.slots(), Map.of("candidateCount", candidates.size(), "candidates", candidates));

        // B-精排：7 维 overlap 打分 + excludeMealIds 过滤，取 top10
        List<MealItem> ranked = mealRankService.rank(new MealRankRequest(candidates, state.slots(), excludeMealIds));
        agentTraceService.recordEvent("MEAL_RANKED", "RANK", Map.of("excludeMealIds", excludeMealIds), Map.of("rankedCount", ranked.size(), "ranked", ranked));

        // B-1 空结果短路：候选为 0 时按 sourceMode 返回不同引导文案，不调 LLM
        if (ranked.isEmpty()) {
            ResponseResult empty = ResponseResult.textOnly(state.sourceMode() == SourceMode.PERSONAL
                    ? "你当前的个人餐食库里暂时没有匹配的餐食，可以补充更多饭堂菜，或者切换到公共餐食数据试试。"
                    : "公共餐食库里暂时没有很匹配的结果，你可以切换个人模式补充餐次、口味或想吃的菜系。");
            agentTraceService.recordEvent("NO_MEAL_MATCHED", "RECOMMEND", state, empty);
            return completeTextOnly(sessionId, traceId, state, state.currentIntent(), empty);
        }

        // C-LLM 生成推荐文案：top10 → top3 传给 RecommendResponseAgent（qwen-max），输出推荐理由 + 口语回复
        RecommendResponseAgentService.Result merged = recommendResponseAgentService.recommendAndRespond(
                sessionId, userInput, state.sourceMode(), state.slots(), ranked);
        RecommendResult recommend = merged.recommend();
        ResponseResult response = merged.response();

        // D-安全审查：RiskGuard 扫描用户原文+LLM回复，命中健康风险关键词则替换为保守文案
        RiskGuardResult guard = riskGuardService.check(userInput, state.currentIntent(), recommend, response);
        agentTraceService.recordEvent("NUTRITION_GUARD_CHECKED", "GUARD", Map.of("intent", state.currentIntent(), "response", response), guard);
        if (!guard.passed()) {
            response = ResponseResult.textOnly(guard.rewriteSuggestion());
            agentTraceService.recordEvent("NUTRITION_GUARD_REWRITTEN", "GUARD", guard, response);
        } else {
            agentTraceService.recordEvent("COMPLIANCE_GUARD_REWRITTEN", "GUARD", null, response);
        }

        // E-持久化状态：累积 lastRecommendations（供"换一批"排除），UPDATE diet_sessions + INSERT diet_messages
        List<Long> lastIds = recommend.recommendations().stream().map(option -> option.itemId()).toList();
        SessionState savedState = state.appendLastRecommendations(lastIds);
        sessionStateService.save(savedState);
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), state.currentIntent().name(), traceId);

        // F-构造 ANSWER 响应：speechText + 餐食卡片 displayBlocks + nextAction=WAIT_USER
        ChatResponse chatResponse = ChatResponse.answer(sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction());
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", savedState, chatResponse);
        return chatResponse;
    }

    /**
     * 纯文本分支的统一收尾：更新 intent → 保存状态 → 落库消息 → 返回 ChatResponse。
     */
    private ChatResponse completeTextOnly(String sessionId, String traceId, SessionState state, Intent intent, ResponseResult response) {
        // 将会话 currentIntent 更新为传入的 intent 枚举
        SessionState savedState = state.withIntent(intent);
        // 将更新后的会话状态 UPDATE 到 diet_sessions 表
        sessionStateService.save(savedState);

        // 将助手纯文本回复 INSERT 到 diet_messages
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), intent.name(), traceId);

        // 构造 ChatResponse（无餐食卡片，displayBlocks 为空）
        ChatResponse chatResponse = ChatResponse.answer(sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction());

        // Trace 事件：RESPONSE_READY | 阶段 RESPONSE | 输入=intent+savedState | 输出=ChatResponse
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", Map.of("intent", intent, "state", savedState), chatResponse);

        // 返回纯文本响应
        return chatResponse;
    }

    /**
     * 将纳秒级开始时间戳转为毫秒耗时。
     */
    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * 构造 Trace payload Map，支持 key-value 交替传入，允许 value 为 null。
     */
    private Map<String, Object> traceMap(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        // 每两个元素为一组 key-value，步长 2 遍历
        for (int i = 0; i + 1 < entries.length; i += 2) {
            // 将 key 转 String 后与 value 放入 Map
            result.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return result;
    }
}
