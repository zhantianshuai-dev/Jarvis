package com.zhan.jarvis.eval;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationScenarioResourceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void containsTwelveUniqueExecutableScenarios() throws Exception {
        EvaluationScenario[] scenarios;
        try (var input = new ClassPathResource("evals/scenarios.json").getInputStream()) {
            scenarios = objectMapper.readValue(input, EvaluationScenario[].class);
        }

        assertThat(scenarios).hasSize(12);
        assertThat(Arrays.stream(scenarios).map(EvaluationScenario::id)).doesNotHaveDuplicates();
        assertThat(List.of(scenarios))
                .allSatisfy(scenario -> {
                    assertThat(scenario.mode()).isNotBlank();
                    assertThat(scenario.prompt()).isNotBlank();
                    assertThat(scenario.safeAssertions()).isNotEmpty();
                });
    }
}
