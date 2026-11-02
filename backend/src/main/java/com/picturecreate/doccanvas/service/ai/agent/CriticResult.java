package com.picturecreate.doccanvas.service.ai.agent;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一次语义审查的结果。
 * nodeTitles 用于把问题里的临时节点 id 还原成标题，回填给 Supervisor 修正时更直观。
 */
public record CriticResult(List<CriticIssue> issues, Map<String, String> nodeTitles, int promptTokens,
        int completionTokens) {

    public static CriticResult none() {
        return new CriticResult(List.of(), Map.of(), 0, 0);
    }

    /** 存在 HIGH 问题时才触发一轮修正，MEDIUM/LOW 只记录不返工 */
    public boolean hasBlockingIssues() {
        return issues.stream().anyMatch(issue -> issue.severity() == CriticIssue.Severity.HIGH);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    /** 给用户看的简短结果，追加在步骤结果后面 */
    public String describe() {
        if (issues.isEmpty()) {
            return "；审查未发现问题";
        }
        long high = issues.stream().filter(issue -> issue.severity() == CriticIssue.Severity.HIGH).count();
        return "；审查发现 " + issues.size() + " 个问题（高优先级 " + high + "）";
    }

    /** 修正反馈：把问题整理成可执行的修改要求，交给 Supervisor 重新生成 */
    public String renderForRepair() {
        String details = issues.stream()
                .map(this::renderIssue)
                .collect(Collectors.joining("\n"));
        return "\n\n审查意见（上一版结果存在以下问题，请逐条修正后重新输出完整内容）：\n" + details;
    }

    private String renderIssue(CriticIssue issue) {
        StringBuilder line = new StringBuilder();
        line.append("- [").append(issue.severity()).append("][").append(issue.type()).append("] ");
        String title = issue.nodeId() == null ? null : nodeTitles.get(issue.nodeId());
        if (title != null) {
            line.append("节点「").append(title).append("」：");
        }
        line.append(issue.message());
        if (issue.suggestion() != null && !issue.suggestion().isBlank()) {
            line.append(" 建议：").append(issue.suggestion());
        }
        return line.toString();
    }
}
