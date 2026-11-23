package io.github.shubhajit93.campaign.service.domain;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal {{placeholder}} renderer. Unknown placeholders are left as they are, so mistakes stay visible. */
public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    private TemplateRenderer() {
    }

    public static String render(String template, Map<String, String> variables) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
