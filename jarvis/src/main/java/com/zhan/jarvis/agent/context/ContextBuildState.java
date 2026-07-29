package com.zhan.jarvis.agent.context;

import com.zhan.jarvis.llm.Message;

import java.util.ArrayList;
import java.util.List;

public class ContextBuildState {

    private final List<Message> messages = new ArrayList<>();
    private boolean currentMessageAdded;

    public List<Message> messages() {
        return messages;
    }

    public boolean currentMessageAdded() {
        return currentMessageAdded;
    }

    public void markCurrentMessageAdded() {
        this.currentMessageAdded = true;
    }
}
