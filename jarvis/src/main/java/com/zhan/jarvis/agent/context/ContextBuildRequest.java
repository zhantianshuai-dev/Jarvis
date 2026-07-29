package com.zhan.jarvis.agent.context;

import com.zhan.jarvis.agent.RunMode;
import com.zhan.jarvis.session.Session;

public record ContextBuildRequest(
        Session session,
        String currentMessage,
        int historyRounds,
        RunMode runMode,
        String workspace
) {
}
