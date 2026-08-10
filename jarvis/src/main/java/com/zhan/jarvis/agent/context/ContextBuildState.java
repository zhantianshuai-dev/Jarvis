package com.zhan.jarvis.agent.context;

import com.zhan.jarvis.llm.Message;

import java.util.ArrayList;
import java.util.List;

public class ContextBuildState {

    private final List<Message> messages = new ArrayList<>();
    private boolean currentMessageAdded;
    private int sessionMessageCount = -1;

    public List<Message> messages() {
        return messages;
    }

    public boolean currentMessageAdded() {
        return currentMessageAdded;
    }

    public void markCurrentMessageAdded() {
        this.currentMessageAdded = true;
    }

    public int sessionMessageCount() {
        return sessionMessageCount;
    }

    public void sessionMessageCount(int sessionMessageCount) {
        this.sessionMessageCount = Math.max(0, sessionMessageCount);
    }
}
