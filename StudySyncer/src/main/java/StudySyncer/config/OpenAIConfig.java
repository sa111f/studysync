package StudySyncer.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OpenAI API configuration, bound from the {@code openai.api.*} properties.
 * When the key is blank (env var unset) the app still boots normally —
 * the AI parser services check {@link #isConfigured()} and short-circuit
 * to AI_UNAVAILABLE, which the controller maps to 503.
 */
@Component
@ConfigurationProperties(prefix = "openai.api")
public class OpenAIConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenAIConfig.class);

    private String key;
    private String url;
    private String model;
    private int    timeoutSeconds = 15;

    @PostConstruct
    void logStartup() {
        if (!isConfigured()) {
            log.warn("[AI] openai.api.key is blank — AI parsing is DISABLED. " +
                     "Set OPENAI_API_KEY in the environment to enable the /api/ai/* endpoints.");
        } else {
            log.info("[AI] OpenAI client configured — model='{}', timeout={}s", model, timeoutSeconds);
        }
    }

    /** True when a non-blank API key is present. */
    public boolean isConfigured() {
        return key != null && !key.isBlank();
    }

    // ── Getters / setters (required by @ConfigurationProperties binding) ──────

    public String getKey()            { return key; }
    public String getUrl()            { return url; }
    public String getModel()          { return model; }
    public int    getTimeoutSeconds() { return timeoutSeconds; }

    public void setKey(String key)                    { this.key = key; }
    public void setUrl(String url)                    { this.url = url; }
    public void setModel(String model)                { this.model = model; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
