package StudySyncer;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "index";
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
}
