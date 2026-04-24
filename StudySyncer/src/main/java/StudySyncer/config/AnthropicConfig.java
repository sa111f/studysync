package StudySyncer.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Anthropic Claude API configuration, bound from the `anthropic.api.*`
 * properties. When the key is blank (env var unset) the app still boots
 * normally — AiTaskParserService checks {@link #isConfigured()} and
 * short-circuits to AI_UNAVAILABLE, which the controller maps to 503.
 *
 * Pattern matches {@code EmailService}'s graceful-degradation approach:
 * never crash on missing credentials, surface a clear "feature disabled"
 * state to the frontend instead.
 */
@Component
@ConfigurationProperties(prefix = "anthropic.api")
public class AnthropicConfig {

    private static final Logger log = LoggerFactory.getLogger(AnthropicConfig.class);

    private String key;
    private String url;
    private String model;
    private int    timeoutSeconds;

    @PostConstruct
    void logStartup() {
        if (!isConfigured()) {
            log.warn("[AI] anthropic.api.key is blank — AI task parsing is DISABLED. " +
                     "Set ANTHROPIC_API_KEY in the environment to enable the /api/ai/parse-task endpoint.");
        } else {
            // Never log the key value — only confirm that it's present + which model is selected.
            log.info("[AI] Anthropic client configured — model='{}', timeout={}s", model, timeoutSeconds);
        }
    }

    /** True when a non-blank API key is present. */
    public boolean isConfigured() {
        return key != null && !key.isBlank();
    }

    // ── Getters / setters (required by @ConfigurationProperties binding) ─────

    public String getKey()             { return key; }
    public String getUrl()             { return url; }
    public String getModel()           { return model; }
    public int    getTimeoutSeconds()  { return timeoutSeconds; }

    public void setKey(String key)                      { this.key = key; }
    public void setUrl(String url)                      { this.url = url; }
    public void setModel(String model)                  { this.model = model; }
    public void setTimeoutSeconds(int timeoutSeconds)   { this.timeoutSeconds = timeoutSeconds; }
}
