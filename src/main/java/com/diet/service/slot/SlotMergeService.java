package com.diet.service.slot;

import com.diet.model.SlotBundle;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 槽位合并服务。
 * 多轮对话中，本轮非空槽位覆盖历史槽位，本轮空槽位不清空历史槽位。
 */
@Service
public class SlotMergeService {

    /**
     * 合并历史槽位与本轮槽位。
     * 由 Orchestrator/IntentRevise 调用，7 维字段各自独立合并。
     */
    public SlotBundle merge(SlotBundle history, SlotBundle current) {
        // A-空值保护：传 null 时用空 SlotBundle 代替，避免 NPE
        SlotBundle safeHistory = history == null ? SlotBundle.empty() : history;
        SlotBundle safeCurrent = current == null ? SlotBundle.empty() : current;

        // B-逐字段合并：7 个槽位维度各自独立判断——本轮有值用本轮，本轮为空保留历史
        return new SlotBundle(
                choose(safeHistory.mealTime(), safeCurrent.mealTime()),
                choose(safeHistory.mood(), safeCurrent.mood()),
                choose(safeHistory.scene(), safeCurrent.scene()),
                choose(safeHistory.healthGoal(), safeCurrent.healthGoal()),
                choose(safeHistory.cuisine(), safeCurrent.cuisine()),
                choose(safeHistory.taste(), safeCurrent.taste()),
                choose(safeHistory.convenience(), safeCurrent.convenience())
        );
    }

    /** 单字段合并规则：本轮非空则覆盖，本轮为空则保留历史。 */
    private List<String> choose(List<String> history, List<String> current) {
        return current == null || current.isEmpty() ? history : current;
    }
}