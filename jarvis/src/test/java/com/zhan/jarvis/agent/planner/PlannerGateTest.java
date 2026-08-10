package com.zhan.jarvis.agent.planner;

import com.zhan.jarvis.agent.RunMode;
import com.zhan.jarvis.config.JarvisConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerGateTest {

    private final PlannerGate gate = new PlannerGate(new JarvisConfig.PlannerConfig(true, true, 5, 12));

    @Test
    void singleGitStatusDoesNotCreatePlan() {
        assertFalse(gate.shouldPlan(RunMode.AGENT, "请查看当前 git 状态，并简要说明有哪些未提交修改。"));
        assertFalse(gate.shouldPlan(RunMode.SUPER_AGENT, "请查看当前 git 状态，并简要说明有哪些未提交修改。"));
    }

    @Test
    void dependentMultiStepTaskStillCreatesPlan() {
        assertTrue(gate.shouldPlan(RunMode.AGENT, "先读取 README.md，然后创建 summary.txt 写入项目名称，并维护 Todo 状态。"));
    }
}
