package io.github.shubhajit93.campaign.service.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRendererTest {

    @Test
    void replacesKnownPlaceholders() {
        assertThat(TemplateRenderer.render("Hi {{name}}, code {{ code }}", Map.of("name", "Ana", "code", "42")))
                .isEqualTo("Hi Ana, code 42");
    }

    @Test
    void leavesUnknownPlaceholdersVisible() {
        assertThat(TemplateRenderer.render("Hi {{missing}}", Map.of())).isEqualTo("Hi {{missing}}");
    }

    @Test
    void treatsReplacementValuesLiterally() {
        assertThat(TemplateRenderer.render("Pay {{amount}}", Map.of("amount", "$5 \\ off")))
                .isEqualTo("Pay $5 \\ off");
    }
}
