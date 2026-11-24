package com.picturecreate.doccanvas.service.ai.agent;

import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturecreate.doccanvas.domain.AgentStepType;
import com.picturecreate.doccanvas.service.ai.BoardReadTools;
import com.picturecreate.doccanvas.service.ai.llm.BudgetedToolCallback;
import com.picturecreate.doccanvas.service.ai.llm.ToolCallListener;

/**
 * 按运行角色组装工具，并统一包装预算与轨迹。
 * 工具权限由角色硬编码，不依赖 SKILL.md 的 allowed_tools，模型无法越界。
 */
@Profile("!lite")
@Component
public class AgentToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentToolRegistry.class);

    /** 生成阶段可用的读工具：写画布统一走「文档 → 解析 → 应用」，不暴露写工具 */
    private static final Set<String> READ_TOOLS = Set.of(
            "get_board_summary", "get_node", "get_subtree", "search_nodes", "list_selected",
            "list_source_files", "search_document_chunks", "web_search");
    /** 预检工具：只读 + reply/start_plan，修改必须走后台计划任务 */
    private static final Set<String> PREFLIGHT_TOOLS = Set.of(
            "get_board_summary", "get_node", "search_nodes", "list_selected",
            "list_source_files", "search_document_chunks", "web_search", "reply", "start_plan");

    private final Map<String, ToolCallback> callbacks = new HashMap<>();
    private final AgentRunRecorder recorder;
    private final ObjectMapper objectMapper;

    public AgentToolRegistry(BoardReadTools readTools, AgentControlTools controlTools,
            AgentRunRecorder recorder, ObjectMapper objectMapper) {
        this.recorder = recorder;
        this.objectMapper = objectMapper;
        register(readTools);
        register(controlTools);
        // 启动时打印一次注册结果，排查“模型看不到某个工具”时先看这里
        log.info("Agent 工具注册完成：数量={} 工具={}", callbacks.size(), callbacks.keySet());
    }

    private void register(Object toolObject) {
        ToolCallback[] provided = MethodToolCallbackProvider.builder()
                .toolObjects(toolObject)
                .build()
                .getToolCallbacks();
        for (ToolCallback callback : provided) {
            callbacks.put(callback.getToolDefinition().name(), callback);
        }
    }

    /** 后台生成阶段的工具：只读，写画布由程序解析模型输出后统一执行 */
    public ToolCallback[] generationTools(AgentExecutionContext context) {
        return wrap(READ_TOOLS, context);
    }

    /** 供提示词列出实际暴露的工具名 */
    public List<String> generationToolNames() {
        return names(READ_TOOLS);
    }

    /** 同步预检的工具：只读 + reply/start_plan */
    public ToolCallback[] preflightTools(AgentExecutionContext context) {
        return wrap(PREFLIGHT_TOOLS, context);
    }

    /** 供提示词列出实际暴露的工具名 */
    public List<String> names(Set<String> requested) {
        return requested.stream().filter(callbacks::containsKey).toList();
    }

    public List<String> preflightToolNames() {
        return names(PREFLIGHT_TOOLS);
    }

    private ToolCallback[] wrap(Set<String> names, AgentExecutionContext context) {
        List<ToolCallback> result = new ArrayList<>();
        ToolCallListener listener = new StepRecordingListener(context);
        for (String name : names) {
            ToolCallback callback = callbacks.get(name);
            if (callback != null) {
                result.add(new BudgetedToolCallback(callback, context.runtime(), listener));
            }
        }
        return result.toArray(ToolCallback[]::new);
    }

    /** 每次工具调用写入 agent_steps，失败不影响工具调用本身 */
    private final class StepRecordingListener implements ToolCallListener {

        private final AgentExecutionContext context;

        private StepRecordingListener(AgentExecutionContext context) {
            this.context = context;
        }

        @Override
        public void onCall(String toolName, String input, String output, long latencyMs, String errorMessage) {
            if (context.runId() == null) {
                return;
            }
            try {
                recorder.recordStep(context.runId(), AgentStepType.TOOL_CALL, toolName,
                        jsonNode(input), jsonNode(output), latencyMs, errorMessage);
            } catch (RuntimeException ignored) {
                // 轨迹写入失败只丢审计，不影响本次工具结果
            }
        }

        private JsonNode jsonNode(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return objectMapper.readTree(raw);
            } catch (Exception exception) {
                return objectMapper.getNodeFactory().textNode(raw);
            }
        }
    }
}
