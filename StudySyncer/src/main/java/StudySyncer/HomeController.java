package StudySyncer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class HomeController {

    @Value("${spring.security.oauth2.client.registration.google.client-id:}")
    private String googleClientId;

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @GetMapping("/focus")
    public String focus() {
        return "focus";
    }

    @GetMapping("/privacy")
    public String privacy() {
        return "privacy";
    }

    @GetMapping("/terms")
    public String terms() {
        return "terms";
    }

    @GetMapping("/about")
    public String about() {
        return "about";
    }

    /**
     * Lightweight healthcheck endpoint for Railway (and any other platform probe).
     * No Thymeleaf rendering, no DB calls — returns as fast as possible so the
     * platform marks the deployment healthy before the timeout expires.
     */
    @GetMapping("/health")
    @ResponseBody
    public String health() {
        return "ok";
    }

    /**
     * Gateway before Spring's OAuth2 filter.
     * If Google credentials are not configured, redirects straight to /?error=oauth
     * instead of hitting the filter and producing a white error page.
     */
    @GetMapping("/auth/google-start")
    public String googleStart() {
        if (googleClientId == null || googleClientId.isBlank()) {
            return "redirect:/?error=oauth";
        }
        return "redirect:/oauth2/authorization/google";
    }
}
