package com.zhan.jarvis.agent.context;

import com.zhan.jarvis.llm.Message;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class DynamicReminderStage implements ContextStage {

    @Override
    public void apply(ContextBuildRequest request, ContextBuildState state) {
        String now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
        String workspace = hasText(request.workspace()) ? request.workspace() : "";
        String mode = request.runMode() != null ? request.runMode().value() : "";

        state.messages().add(Message.system("""
                <system-reminder>
                  <current_time>%s</current_time>
                  <workspace>%s</workspace>
                  <run_mode>%s</run_mode>
                </system-reminder>
                """.formatted(escapeXml(now), escapeXml(workspace), escapeXml(mode)).strip()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
