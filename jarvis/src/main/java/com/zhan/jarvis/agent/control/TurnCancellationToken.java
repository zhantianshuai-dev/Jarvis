package com.zhan.jarvis.agent.control;

import java.util.concurrent.CompletionStage;

/**
 * 单次 Agent Run 的只读取消信号。
 */
public interface TurnCancellationToken {

    boolean isCancellationRequested();

    String reason();

    CompletionStage<Void> cancelled();

    AutoCloseable onCancel(Runnable callback);

    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new TurnInterruptedException(reason());
        }
    }

    static TurnCancellationToken none() {
        return TurnCancellationSource.NONE;
    }
}
