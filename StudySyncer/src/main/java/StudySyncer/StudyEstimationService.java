package StudySyncer;

import StudySyncer.dto.StudyEstimationResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;

/**
 * STUD-58/62: Rule-based study time estimation from text heuristics
 *             (word count, page count, topic density, document type).
 * STUD-63:   Optional AI estimation via OpenAI API.
 *             Falls back to rule-based when no key is configured or AI call fails.
 */
@Service
public class StudyEstimationService {

    private final String       apiKey;
    private final String       model;
    private final ObjectMapper objectMapper;

    public StudyEstimationService(
            @Value("${studysyncer.ai.api-key:}") String apiKey,
            @Value("${studysyncer.ai.model:gpt-4o-mini}") String model,
            ObjectMapper objectMapper) {
        this.apiKey       = apiKey;
        this.model        = model;
        this.objectMapper = objectMapper;
    }

    /** Entry point — AI if configured, rule-based otherwise. */
    public StudyEstimationResponse estimate(String text, String difficulty) {
        return estimate(text, difficulty, null, 0, null);
    }

    /** Entry point with optional title (backward-compatible). */
    public StudyEstimationResponse estimate(String text, String difficulty, String title) {
        return estimate(text, difficulty, title, 0, null);
    }

    /** Full entry point — passes all metadata to AI and rule-based paths. */
    public StudyEstimationResponse estimate(String text, String difficulty,
                                            String title, int pageCount, String documentType) {
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        String contentType = classifyContentType(text, title);
        System.out.println("[Estimation] mode=" + (hasKey ? "openai" : "fallback")
                + " pageCount=" + pageCount
                + " words=" + (text != null ? countWords(text) : 0)
                + " docType=" + documentType
                + " contentType=" + contentType);
        if (hasKey) {
            try {
                StudyEstimationResponse result =
                        estimateWithAi(text, difficulty, title, pageCount, documentType, contentType);
                System.out.println("[Estimation] OpenAI succeeded, applying sanity bounds");
                return applySafetyFloors(result, text, pageCount);
            } catch (Exception e) {
                System.out.println("[Estimation] OpenAI failed (" + e.getMessage()
                        + "), falling back to rule-based");
            }
        }
        return estimateRuleBased(text, difficulty, pageCount, documentType, contentType);
    }

    // ── Rule-based estimation ─────────────────────────────────────────────────

    StudyEstimationResponse estimateRuleBased(String text, String difficulty,
                                               int pageCount, String documentType,
                                               String contentType) {
        int wordCount  = countWords(text);
        int topicCount = countTopics(text);
        boolean isSlides = isSlideDocument(documentType);

        double base;

        if (pageCount > 0) {
            // Words-per-page density is more trustworthy than absolute word count because
            // PDF extraction repeats headers/footers on every page, inflating total words.
            double wordsPerPage = (double) wordCount / pageCount;
            double minsPerPage;
            if (isSlides) {
                if      (wordsPerPage < 25)  minsPerPage = 1.8;
                else if (wordsPerPage < 50)  minsPerPage = 2.3;
                else if (wordsPerPage < 80)  minsPerPage = 2.8;
                else                          minsPerPage = 3.2;
            } else {
                if      (wordsPerPage < 150) minsPerPage = 2.5;
                else if (wordsPerPage < 300) minsPerPage = 3.5;
                else                          minsPerPage = 4.5;
            }
            base = pageCount * minsPerPage;
        } else {
            if      (wordCount < 300)  base = 20;
            else if (wordCount < 600)  base = 35;
            else if (wordCount < 1200) base = 55;
            else if (wordCount < 2500) base = 80;
            else if (wordCount < 4000) base = 110;
            else                       base = 140;
        }

        // Topic count provides a secondary signal for conceptual breadth
        if      (topicCount >= 12) base = Math.max(base, 130);
        else if (topicCount >= 8)  base = Math.max(base, 100);
        else if (topicCount >= 5)  base = Math.max(base, 75);

        // Content-type multiplier: science material has higher cognitive load than
        // procedural coding content — more memorization, derivations, abstract concepts.
        String ct = (contentType != null) ? contentType : "general";
        double contentMult = switch (ct) {
            case "science"    -> 1.20;   // chemistry/physics/materials: heavier load
            case "procedural" -> 0.95;   // coding/CS: practice-based, faster internalization
            default           -> 1.00;
        };
        if (!"general".equals(ct)) {
            System.out.println("[RuleBased] contentType=" + ct + " mult=" + contentMult);
        }
        base = base * contentMult;

        // Difficulty multiplier
        double diffMult = switch ((difficulty != null ? difficulty : "medium").toLowerCase()) {
            case "easy" -> 0.75;
            case "hard" -> 1.25;
            default     -> 1.0;
        };

        double standard = base * diffMult;
        double light    = standard * 0.55;
        double deep     = standard * 1.60;

        int stdMin   = clamp((int) Math.round(standard), 20, 270);
        int lightMin = clamp((int) Math.round(light),    10, Math.min(stdMin - 10, 130));
        int deepMin  = clamp((int) Math.round(deep),     stdMin + 15, 400);

        if (pageCount >= 45 || topicCount >= 8) {
            lightMin = Math.max(lightMin, 50);
            stdMin   = Math.max(stdMin,   110);
            deepMin  = Math.max(deepMin,  170);
        } else if (pageCount >= 25 || topicCount >= 5) {
            lightMin = Math.max(lightMin, 30);
            stdMin   = Math.max(stdMin,    65);
            deepMin  = Math.max(deepMin,  105);
        }

        if (stdMin <= lightMin) stdMin  = lightMin + 20;
        if (deepMin <= stdMin)  deepMin = stdMin   + 30;

        int pages = pageCount > 0 ? pageCount : estimatePageCount(wordCount);
        System.out.println("[RuleBased] pages=" + pages + " words=" + wordCount
                + " topics=" + topicCount + " isSlides=" + isSlides
                + " base=" + (int) base
                + " → light=" + lightMin + " standard=" + stdMin + " deep=" + deepMin);

        String rationale = buildRationale(wordCount, pages, topicCount, difficulty, isSlides);
        return new StudyEstimationResponse(lightMin, stdMin, deepMin, rationale, "fallback");
    }

    // Backward-compatible overload (no contentType — classifies internally)
    StudyEstimationResponse estimateRuleBased(String text, String difficulty,
                                               int pageCount, String documentType) {
        return estimateRuleBased(text, difficulty, pageCount, documentType,
                classifyContentType(text, null));
    }

    // Convenience overload for callers with no page/type metadata
    StudyEstimationResponse estimateRuleBased(String text, String difficulty) {
        return estimateRuleBased(text, difficulty, 0, null, classifyContentType(text, null));
    }

    private String buildRationale(int words, int pages, int topics,
                                   String difficulty, boolean slides) {
        StringBuilder sb = new StringBuilder();
        sb.append(words).append(" words");
        if (pages > 0) sb.append(", ").append(pages).append(" pages");
        if (topics > 0) sb.append(", ~").append(topics).append(" topics");
        sb.append(", ").append(difficulty != null ? difficulty : "medium").append(" difficulty");
        if (slides)     sb.append(", lecture slides");
        sb.append(" (local estimate)");
        return sb.toString();
    }

    // ── AI estimation ─────────────────────────────────────────────────────────

    private StudyEstimationResponse estimateWithAi(String text, String difficulty,
                                                    String title, int pageCount,
                                                    String documentType, String contentType)
            throws Exception {
        int    wordCount  = countWords(text);
        int    topicCount = countTopics(text);
        int    pages      = pageCount > 0 ? pageCount : estimatePageCount(wordCount);
        String docType    = (documentType != null && !documentType.isBlank())
                            ? documentType : "lecture PDF / slide deck";

        // Keep text under 4000 chars to leave room for prompt and response tokens
        String truncated = text.length() > 4000 ? text.substring(0, 4000) + "..." : text;

        String titleLine = (title != null && !title.isBlank())
                           ? "- Title: " + title + "\n" : "";

        // ── Content-type-specific calibration and guidance ─────────────────────
        // Each content type gets a distinct calibration table and reasoning guidance
        // so the model can correctly differentiate dense science from coding lectures.
        String contentGuidance;
        if ("science".equals(contentType)) {
            contentGuidance =
                "Detected content type: SCIENCE (chemistry, materials science, biology, physics)\n"
                + "Science material demands understanding abstract concepts, memorising nomenclature\n"
                + "and formulas, and reconstructing derivations. Estimate GENEROUSLY — this type of\n"
                + "material has high cognitive load that a page count alone understates.\n\n"
                + "Science calibration reference (use the closest page-count match):\n"
                + "  Science lecture, ~30 slides, moderate formulas:            "
                + "light=55,  standard=115, deep=185\n"
                + "  Chemistry/materials, ~45 slides, reactions + derivations:  "
                + "light=80,  standard=165, deep=260\n"
                + "  Dense science chapter, ~55 slides, heavy conceptual load:  "
                + "light=90,  standard=185, deep=295\n"
                + "  Heavy science, ~65+ slides, many proofs/derivations:       "
                + "light=105, standard=215, deep=340\n";
        } else if ("procedural".equals(contentType)) {
            contentGuidance =
                "Detected content type: PROCEDURAL (programming, algorithms, CS, software)\n"
                + "Coding content is practice-based — students build intuition through examples\n"
                + "faster than with abstract theory. Estimate moderately.\n\n"
                + "Coding calibration reference (use the closest page-count match):\n"
                + "  Intro coding lecture, ~25 slides, practical examples:      "
                + "light=30, standard=55,  deep=90\n"
                + "  Mid-level CS lecture, ~35 slides, moderate complexity:     "
                + "light=45, standard=85,  deep=135\n"
                + "  Dense CS/algorithms, ~45 slides, some theory + proofs:     "
                + "light=55, standard=110, deep=175\n"
                + "  Advanced CS theory, ~55 slides, heavy abstractions:        "
                + "light=65, standard=130, deep=210\n";
        } else {
            contentGuidance =
                "Detected content type: GENERAL\n\n"
                + "General calibration reference (use the closest page-count match):\n"
                + "  Introductory material, ~25 slides:                         "
                + "light=30, standard=60,  deep=95\n"
                + "  Moderate lecture, ~35 slides:                              "
                + "light=45, standard=90,  deep=145\n"
                + "  Content-rich lecture, ~50 slides:                          "
                + "light=65, standard=130, deep=210\n"
                + "  Dense comprehensive material, ~65+ slides:                 "
                + "light=80, standard=160, deep=255\n";
        }

        String prompt =
                "You are a university study workload estimator.\n\n"
                + "Document:\n"
                + titleLine
                + "- Type: " + docType + "\n"
                + "- Pages/slides: " + (pages > 0 ? pages : "unknown") + "\n"
                + "- Extracted word count: " + wordCount
                + " (includes PDF headers/footers — treat as approximate)\n"
                + "- Topic sections detected: " + (topicCount > 0 ? topicCount : "unknown") + "\n\n"
                + contentGuidance + "\n"
                + "Time definitions:\n"
                + "  lightMinutes    : Quick skim, identify main ideas, no note-taking.\n"
                + "  standardMinutes : Understand all concepts, review examples, brief notes.\n"
                + "  deepMinutes     : Master material, work examples independently, self-test.\n\n"
                + "Estimation rules:\n"
                + "1. Use PAGE COUNT as your primary signal, matched against the table above.\n"
                + "2. standardMinutes must be 1.5× to 2.0× lightMinutes.\n"
                + "3. deepMinutes must be 1.4× to 2.0× standardMinutes.\n"
                + "4. Adjust UPWARD from the calibration entry if the content shows: heavy formula\n"
                + "   density, abstract theory requiring derivation, extensive nomenclature to\n"
                + "   memorise, or diagrams that require interpretation rather than just viewing.\n"
                + "5. Adjust downward only if: content is clearly introductory, mostly review,\n"
                + "   or dominated by simple worked examples with little conceptual depth.\n\n"
                + "Return ONLY valid JSON, no markdown, no extra text:\n"
                + "{\"lightMinutes\": integer, \"standardMinutes\": integer, "
                + "\"deepMinutes\": integer, \"reason\": \"one sentence under 100 chars\"}\n\n"
                + "Material (extracted text):\n" + truncated;

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", prompt);
        body.put("max_tokens", 250);
        body.put("temperature", 0.2);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(20))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("AI API returned status " + response.statusCode());
        }

        JsonNode root    = objectMapper.readTree(response.body());
        String   content = root.path("choices").get(0).path("message").path("content").asText();

        // Strip any markdown fences the model might add despite instructions
        content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        JsonNode est = objectMapper.readTree(content);
        int light    = est.path("lightMinutes").asInt(0);
        int standard = est.path("standardMinutes").asInt(0);
        int deep     = est.path("deepMinutes").asInt(0);

        // Require all three fields to be non-zero; otherwise the parse was bad
        if (light == 0 || standard == 0 || deep == 0) {
            throw new RuntimeException("AI returned incomplete time estimates");
        }

        System.out.println("[OpenAI] raw response: light=" + light
                + " standard=" + standard + " deep=" + deep);

        String reason = est.path("reason").asText("AI estimate");
        return new StudyEstimationResponse(light, standard, deep, reason, "openai");
    }

    // ── Safety heuristics (applied after AI response) ────────────────────────

    /**
     * Applied after a successful OpenAI response — NOT after rule-based fallback.
     *
     * DESIGN PRINCIPLE: OpenAI already analysed the document content and returned
     * differentiated, calibrated estimates. This method must NOT replace those values
     * with heuristic floors; doing so defeats the purpose of using AI.
     *
     * Responsibilities here are limited to three sanity checks:
     *   1. Fix ordering if the AI returned values out of sequence (light >= standard, etc.).
     *   2. Enforce absolute global minimums (nothing below 10/20/30 min).
     *   3. Log clearly whether any adjustment was made and why.
     *
     * Heuristic floors (page count, topic count, word count) belong in estimateRuleBased,
     * not here. They are too coarse to improve on what OpenAI already computed from the
     * actual document text.
     */
    private StudyEstimationResponse applySafetyFloors(StudyEstimationResponse r,
                                                       String text, int pageCount) {
        int light    = r.getMinMinutes();
        int standard = r.getRecommendedMinutes();
        int deep     = r.getMaxMinutes();

        System.out.println("[OpenAISanity] Input: light=" + light
                + " standard=" + standard + " deep=" + deep);

        boolean adjusted = false;

        // Fix ordering if the AI returned garbled values (rare but possible)
        if (standard <= light) {
            standard = Math.max(standard, (int) Math.round(light * 1.7));
            adjusted = true;
            System.out.println("[OpenAISanity] Reordered: standard raised to " + standard
                    + " (was <= light=" + light + ")");
        }
        if (deep <= standard) {
            deep = Math.max(deep, (int) Math.round(standard * 1.5));
            adjusted = true;
            System.out.println("[OpenAISanity] Reordered: deep raised to " + deep
                    + " (was <= standard=" + standard + ")");
        }

        // Absolute global minimums — values below these are nonsensical regardless of document
        if (light    < 10) { light    = 10; adjusted = true; }
        if (standard < 20) { standard = 20; adjusted = true; }
        if (deep     < 30) { deep     = 30; adjusted = true; }

        // Re-enforce ordering after minimums (in case minimums disturbed the sequence)
        if (standard <= light)    { standard = light    + 15; adjusted = true; }
        if (deep     <= standard) { deep     = standard + 20; adjusted = true; }

        System.out.println("[OpenAISanity] Final: light=" + light
                + " standard=" + standard + " deep=" + deep
                + (adjusted ? " (adjusted — see above)" : " (unchanged — AI estimate used as-is)"));

        return new StudyEstimationResponse(light, standard, deep,
                r.getRationale(), r.getEstimateSource());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }

    private int countNonEmptyLines(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int) Arrays.stream(text.split("\n"))
                .filter(l -> !l.trim().isEmpty())
                .count();
    }

    private int countStructuredLines(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int) Arrays.stream(text.split("\n"))
                .filter(l -> {
                    String t = l.trim();
                    return t.startsWith("#") || t.startsWith("*") || t.startsWith("-")
                            || t.startsWith("•") || t.matches("^\\d+[.)].+");
                })
                .count();
    }

    /**
     * Counts meaningful section headings as a proxy for the number of major topics.
     *
     * IMPORTANT: The old pattern "^\d+[.)].{1,60}" matched every numbered bullet point
     * ("1. First point", "2. Second point", ...) causing topics=63 for a 32-page PDF.
     * That inflated topicFloor to 630, which then drove safety floors to override correct
     * OpenAI estimates. The numbered-bullet pattern is intentionally removed here.
     *
     * Deduplication via .distinct() prevents slide headers that repeat on every page
     * (e.g., course name in the footer) from being counted multiple times.
     */
    private int countTopics(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int) Arrays.stream(text.split("\n"))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .distinct()   // repeated headers across slides count as one topic
                .filter(t -> {
                    // Markdown headings (#, ##, ###)
                    if (t.startsWith("#")) return true;
                    // ALL-CAPS slide titles: letters, digits, spaces, hyphens, colons
                    // Length 4–80 avoids matching single-word noise or entire paragraphs
                    if (t.length() >= 4 && t.length() <= 80
                            && t.equals(t.toUpperCase())
                            && t.matches("[A-Z][A-Z0-9 \\-:/]+")) return true;
                    // Explicit section keywords followed by a number ("Lecture 3", "Chapter 2")
                    if (t.matches("(?i)^(chapter|section|topic|part|module|lecture|unit)\\s+\\d+.*"))
                        return true;
                    return false;
                })
                .count();
    }

    /**
     * Rough page estimate when page count is not provided.
     * Assumes ~250 words per page (average for lecture notes/slides).
     */
    private int estimatePageCount(int wordCount) {
        return Math.max(1, wordCount / 250);
    }

    /**
     * Classifies the document as "science", "procedural", or "general" by scanning
     * the title and the first 1500 characters of extracted text for domain keywords.
     *
     * Used to select the correct calibration table in the AI prompt and to apply a
     * content-type multiplier in the fallback estimator. Science material (chemistry,
     * materials, physics, biology) has substantially higher cognitive load than
     * procedural coding content — it requires memorisation, derivation, and abstract
     * conceptual understanding that page count alone does not fully capture.
     */
    private String classifyContentType(String text, String title) {
        String sample = (
                (title != null ? title : "")
                + " "
                + (text  != null ? text.substring(0, Math.min(text.length(), 1500)) : "")
        ).toLowerCase();

        int scienceScore = 0;
        for (String kw : new String[]{
                "chemistry", "chemical", "molecule", "reaction", "reagent", "compound",
                "materials", "crystal", "lattice", "polymer", "semiconductor", "alloy",
                "biology", "biological", "protein", "enzyme", "dna", "rna", "genome",
                "physics", "thermodynamics", "quantum", "electromagnetic", "mechanics",
                "organic", "inorganic", "periodic", "element", "bonding", "valence",
                "entropy", "enthalpy", "gibbs", "equilibrium", "kinetics", "diffusion",
                "phase diagram", "microstructure", "stoichiometry", "molarity", "mole"}) {
            if (sample.contains(kw)) scienceScore++;
        }

        int proceduralScore = 0;
        for (String kw : new String[]{
                "algorithm", "function", "variable", "array", "loop", "recursion",
                "programming", "python", "java", "javascript", "c++", "runtime",
                "data structure", "sorting", "complexity", "big-o", "binary tree",
                "database", "sql", "object", "method", "compiler", "linked list",
                "hash table", "graph", "dynamic programming", "pseudocode"}) {
            if (sample.contains(kw)) proceduralScore++;
        }

        System.out.println("[ContentType] scienceScore=" + scienceScore
                + " proceduralScore=" + proceduralScore);

        if (scienceScore >= 2 && scienceScore > proceduralScore) return "science";
        if (proceduralScore >= 2 && proceduralScore > scienceScore) return "procedural";
        return "general";
    }

    private boolean isSlideDocument(String documentType) {
        if (documentType == null) return false;
        String lower = documentType.toLowerCase();
        return lower.contains("slide") || lower.contains("lecture") || lower.contains("deck");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
