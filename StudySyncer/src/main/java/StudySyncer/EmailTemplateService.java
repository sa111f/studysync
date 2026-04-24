package StudySyncer;

import StudySyncer.dto.DigestContent;
import StudySyncer.entity.EmailType;
import StudySyncer.entity.Exam;
import StudySyncer.entity.Task;
import StudySyncer.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders HTML + plain-text bodies for every Phase 8 email type.
 *
 * Why inline HTML here (vs. Thymeleaf .html templates)?
 *   - Email HTML needs tables + inline styles; Thymeleaf buys us nothing
 *   - One file to audit for design tokens and unsubscribe-link placement
 *   - No render-context vs. request-context confusion
 *
 * Design tokens — hard-coded hex rather than var(--accent) because most
 * mail clients strip CSS variables:
 *   Accent purple   : #7C5CFC
 *   Success green   : #34d399
 *   Danger red      : #ef4444
 *   Muted text grey : #8b8fa8
 *   Text            : #1a1d27 (NB: dark-mode-safe — mail clients invert)
 *   Surface         : #ffffff (wrapper); container bg #f5f5fa
 *   Max width       : 600 px
 */
@Service
public class EmailTemplateService {

    private static final DateTimeFormatter DATE_LONG =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_SHORT =
            DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter DATETIME_SHORT =
            DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a", Locale.ENGLISH);

    private final UnsubscribeTokenService tokens;
    private final String                  publicBaseUrl;

    public EmailTemplateService(UnsubscribeTokenService tokens,
                                @Value("${studysyncer.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.tokens        = tokens;
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
    }

    // ══════════════════════════════════════════════════════════
    //  DIGEST
    // ══════════════════════════════════════════════════════════

    public String subjectForDigest(DigestContent c) {
        int nT = c.getTasksDueToday() != null ? c.getTasksDueToday().size() : 0;
        int nE = c.getExamsThisWeek() != null ? c.getExamsThisWeek().size() : 0;
        return "Today: " + nT + " task" + s(nT) + " · " + nE + " exam" + s(nE) + " this week — StudySyncer";
    }

    public String renderDigestHtml(User user, DigestContent c) {
        StringBuilder b = sharedHtmlHeader("Good morning, " + esc(firstName(user)) + "!");

        if (!c.getTasksDueToday().isEmpty()) {
            b.append(sectionHeader("🎯 Today"));
            b.append(bulletListHtml(
                    c.getTasksDueToday().stream()
                            .map(t -> esc(t.getTitle()) + courseSuffix(t.getCourse()))
                            .toList()));
        }

        if (!c.getTasksOverdue().isEmpty()) {
            b.append(sectionHeader("⚠️ Overdue (" + c.getTasksOverdue().size() + ")"));
            b.append(bulletListHtml(
                    c.getTasksOverdue().stream()
                            .map(t -> esc(t.getTitle()) + " — "
                                    + daysOverduePhrase(t.getDueDate(), c.getUserLocalDate()))
                            .toList()));
        }

        if (!c.getExamsThisWeek().isEmpty()) {
            b.append(sectionHeader("📅 Exams this week"));
            b.append(bulletListHtml(
                    c.getExamsThisWeek().stream()
                            .map(e -> esc(e.getTitle()) + " — "
                                    + e.getDateTime().format(DATETIME_SHORT)
                                    + (e.getCourse() != null && !e.getCourse().isBlank()
                                        ? " (" + esc(e.getCourse()) + ")" : ""))
                            .toList()));
        }

        if (c.getCurrentStreak() > 0) {
            b.append(paragraph("Your streak: <strong>🔥 " + c.getCurrentStreak()
                    + " day" + s(c.getCurrentStreak()) + "</strong> — keep it going!"));
        }

        b.append(ctaButton("Open StudySyncer →", publicBaseUrl + "/"));
        b.append(sharedHtmlFooter(user, EmailType.DIGEST));
        return b.toString();
    }

    public String renderDigestPlainText(User user, DigestContent c) {
        StringBuilder b = new StringBuilder();
        b.append("Good morning, ").append(firstName(user)).append("!\n\n");

        if (!c.getTasksDueToday().isEmpty()) {
            b.append("🎯 Today\n");
            for (Task t : c.getTasksDueToday()) {
                b.append("  • ").append(t.getTitle()).append(courseSuffixPlain(t.getCourse())).append("\n");
            }
            b.append("\n");
        }

        if (!c.getTasksOverdue().isEmpty()) {
            b.append("⚠️  Overdue (").append(c.getTasksOverdue().size()).append(")\n");
            for (Task t : c.getTasksOverdue()) {
                b.append("  • ").append(t.getTitle()).append(" — ")
                 .append(daysOverduePhrase(t.getDueDate(), c.getUserLocalDate())).append("\n");
            }
            b.append("\n");
        }

        if (!c.getExamsThisWeek().isEmpty()) {
            b.append("📅 Exams this week\n");
            for (Exam e : c.getExamsThisWeek()) {
                b.append("  • ").append(e.getTitle()).append(" — ")
                 .append(e.getDateTime().format(DATETIME_SHORT));
                if (e.getCourse() != null && !e.getCourse().isBlank()) {
                    b.append(" (").append(e.getCourse()).append(")");
                }
                b.append("\n");
            }
            b.append("\n");
        }

        if (c.getCurrentStreak() > 0) {
            b.append("Your streak: 🔥 ").append(c.getCurrentStreak())
             .append(" day").append(s(c.getCurrentStreak())).append(" — keep it going!\n\n");
        }

        b.append("Open StudySyncer: ").append(publicBaseUrl).append("/\n\n");
        b.append(footerPlainText(user, EmailType.DIGEST));
        return b.toString();
    }

    // ══════════════════════════════════════════════════════════
    //  OVERDUE REMINDER
    // ══════════════════════════════════════════════════════════

    public String subjectForOverdue(int count) {
        return "⚠️ " + count + " task" + s(count) + " went overdue yesterday";
    }

    public String renderOverdueHtml(User user, List<Task> overdueTasks, LocalDate today) {
        StringBuilder b = sharedHtmlHeader("You have " + overdueTasks.size()
                + " overdue task" + s(overdueTasks.size()) + ".");
        b.append(paragraph("A quick nudge so nothing slips through:"));
        b.append(bulletListHtml(
                overdueTasks.stream()
                        .map(t -> "<strong>" + esc(t.getTitle()) + "</strong>"
                                + courseSuffix(t.getCourse()) + " — "
                                + daysOverduePhrase(t.getDueDate(), today))
                        .toList()));
        b.append(ctaButton("Open Tasks →", publicBaseUrl + "/tasks"));
        b.append(sharedHtmlFooter(user, EmailType.OVERDUE_REMINDER));
        return b.toString();
    }

    public String renderOverduePlainText(User user, List<Task> overdueTasks, LocalDate today) {
        StringBuilder b = new StringBuilder();
        b.append("You have ").append(overdueTasks.size())
         .append(" overdue task").append(s(overdueTasks.size())).append(".\n\n");
        for (Task t : overdueTasks) {
            b.append("  • ").append(t.getTitle()).append(courseSuffixPlain(t.getCourse()))
             .append(" — ").append(daysOverduePhrase(t.getDueDate(), today)).append("\n");
        }
        b.append("\nOpen Tasks: ").append(publicBaseUrl).append("/tasks\n\n");
        b.append(footerPlainText(user, EmailType.OVERDUE_REMINDER));
        return b.toString();
    }

    // ══════════════════════════════════════════════════════════
    //  EXAM REMINDER
    // ══════════════════════════════════════════════════════════

    public String subjectForExamReminder(Exam exam, int daysUntil) {
        return "📅 Exam in " + daysUntil + " day" + s(daysUntil) + ": " + exam.getTitle();
    }

    public String renderExamReminderHtml(User user, Exam exam, int daysUntil, EmailType type) {
        StringBuilder b = sharedHtmlHeader("Your exam is coming up.");
        b.append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" " +
                "style=\"background:#faf9ff;border:1px solid #e6e3ff;border-radius:8px;" +
                "margin:0 0 16px;\"><tr><td style=\"padding:18px 20px;\">");

        b.append("<div style=\"font-size:18px;font-weight:700;color:#1a1d27;margin:0 0 8px;\">")
         .append(esc(exam.getTitle())).append("</div>");

        b.append("<div style=\"font-size:14px;color:#4a4e68;line-height:1.55;\">");
        b.append("<strong>When:</strong> ").append(exam.getDateTime().format(DATETIME_SHORT));
        b.append(" &nbsp;·&nbsp; <strong>In ").append(daysUntil)
         .append(" day").append(s(daysUntil)).append("</strong><br>");
        if (exam.getCourse() != null && !exam.getCourse().isBlank()) {
            b.append("<strong>Course:</strong> ").append(esc(exam.getCourse())).append("<br>");
        }
        if (exam.getLocation() != null && !exam.getLocation().isBlank()) {
            b.append("<strong>Location:</strong> ").append(esc(exam.getLocation())).append("<br>");
        }
        if (exam.getMaterial() != null && !exam.getMaterial().isBlank()) {
            b.append("<strong>Material:</strong> ").append(esc(exam.getMaterial()));
        }
        b.append("</div></td></tr></table>");

        b.append(paragraph(encouragementFor(daysUntil)));
        b.append(ctaButton("Open Exams →", publicBaseUrl + "/exams"));
        b.append(sharedHtmlFooter(user, type));
        return b.toString();
    }

    public String renderExamReminderPlainText(User user, Exam exam, int daysUntil, EmailType type) {
        StringBuilder b = new StringBuilder();
        b.append("Your exam is in ").append(daysUntil).append(" day").append(s(daysUntil)).append(".\n\n");
        b.append(exam.getTitle()).append("\n");
        b.append("  When:    ").append(exam.getDateTime().format(DATETIME_SHORT)).append("\n");
        if (exam.getCourse() != null && !exam.getCourse().isBlank()) {
            b.append("  Course:  ").append(exam.getCourse()).append("\n");
        }
        if (exam.getLocation() != null && !exam.getLocation().isBlank()) {
            b.append("  Where:   ").append(exam.getLocation()).append("\n");
        }
        if (exam.getMaterial() != null && !exam.getMaterial().isBlank()) {
            b.append("  Material: ").append(exam.getMaterial()).append("\n");
        }
        b.append("\n").append(encouragementFor(daysUntil)).append("\n\n");
        b.append("Open Exams: ").append(publicBaseUrl).append("/exams\n\n");
        b.append(footerPlainText(user, type));
        return b.toString();
    }

    // ══════════════════════════════════════════════════════════
    //  Shared header / footer
    // ══════════════════════════════════════════════════════════

    private StringBuilder sharedHtmlHeader(String heading) {
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
         .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"></head>")
         .append("<body style=\"margin:0;padding:24px 12px;background:#f5f5fa;" +
                 "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#1a1d27;\">");
        // 600px container — mail-client-safe table wrapper.
        b.append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\">" +
                 "<tr><td align=\"center\">" +
                 "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"600\" " +
                 "style=\"max-width:600px;background:#ffffff;border-radius:12px;" +
                 "box-shadow:0 1px 3px rgba(0,0,0,0.05);\">");
        // Header strip with logo.
        b.append("<tr><td style=\"padding:22px 28px 6px;\">" +
                 "<div style=\"font-size:20px;font-weight:700;letter-spacing:-0.01em;\">" +
                 "<span style=\"color:#1a1d27;\">Study</span>" +
                 "<span style=\"color:#7C5CFC;\">Syncer</span></div></td></tr>");
        b.append("<tr><td style=\"padding:6px 28px 0;\">" +
                 "<h1 style=\"margin:10px 0 4px;font-size:22px;line-height:1.3;color:#1a1d27;\">")
         .append(heading).append("</h1></td></tr>");
        // Body container row opens here and is closed by the footer.
        b.append("<tr><td style=\"padding:8px 28px 0;font-size:15px;line-height:1.55;color:#1a1d27;\">");
        return b;
    }

    private String sharedHtmlFooter(User user, EmailType type) {
        String unsubUrl       = publicBaseUrl + "/api/notifications/unsubscribe?token="
                              + tokens.sign(user.getId(), type);
        String manageUrl      = publicBaseUrl + "/";     // logged-in settings live on the homepage
        String recipient      = esc(user.getAccountabilityEmail() != null
                                    ? user.getAccountabilityEmail() : "");
        String typeLabel      = humanTypeLabel(type);

        // Close the body <td>/<tr> opened in the header, then emit footer row.
        return "</td></tr>" +
               "<tr><td style=\"padding:24px 28px 28px;\">" +
                 "<hr style=\"border:none;border-top:1px solid #eceaf5;margin:0 0 14px;\">" +
                 "<div style=\"font-size:12px;color:#8b8fa8;line-height:1.5;\">" +
                   "You're receiving this because email notifications are enabled for your " +
                   "StudySyncer account (sent to <strong>" + recipient + "</strong>)." +
                   "<br><br>" +
                   "<a href=\"" + unsubUrl + "\" style=\"color:#7C5CFC;text-decoration:none;\">" +
                       "Turn off " + typeLabel + " emails</a>" +
                   " &nbsp;·&nbsp; " +
                   "<a href=\"" + manageUrl + "\" style=\"color:#7C5CFC;text-decoration:none;\">" +
                       "Manage all email notifications</a>" +
                 "</div>" +
               "</td></tr></table></td></tr></table></body></html>";
    }

    private String footerPlainText(User user, EmailType type) {
        String unsubUrl  = publicBaseUrl + "/api/notifications/unsubscribe?token="
                        + tokens.sign(user.getId(), type);
        String manageUrl = publicBaseUrl + "/";
        return "---\n"
             + "You're receiving this because email notifications are enabled for your "
             + "StudySyncer account (sent to " + user.getAccountabilityEmail() + ").\n\n"
             + "Turn off " + humanTypeLabel(type) + " emails: " + unsubUrl + "\n"
             + "Manage all email notifications: " + manageUrl + "\n";
    }

    // ══════════════════════════════════════════════════════════
    //  Tiny HTML helpers (kept here to keep email body composition
    //  readable; these are intentionally NOT exported to the app's
    //  wider CSS/JS surface)
    // ══════════════════════════════════════════════════════════

    private String sectionHeader(String text) {
        return "<h2 style=\"margin:16px 0 6px;font-size:16px;color:#1a1d27;\">"
             + text + "</h2>";
    }

    private String paragraph(String htmlInside) {
        return "<p style=\"margin:8px 0;font-size:15px;line-height:1.55;\">"
             + htmlInside + "</p>";
    }

    private String bulletListHtml(List<String> items) {
        StringBuilder b = new StringBuilder(
                "<ul style=\"padding-left:20px;margin:4px 0 12px;font-size:15px;line-height:1.6;\">");
        for (String i : items) b.append("<li style=\"margin:2px 0;\">").append(i).append("</li>");
        return b.append("</ul>").toString();
    }

    private String ctaButton(String label, String href) {
        return "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:18px 0 8px;\">" +
               "<tr><td style=\"background:#7C5CFC;border-radius:8px;\">" +
               "<a href=\"" + href + "\" " +
               "style=\"display:inline-block;padding:10px 18px;color:#ffffff;text-decoration:none;" +
               "font-weight:600;font-size:14px;font-family:inherit;\">" + label + "</a>" +
               "</td></tr></table>";
    }

    private String humanTypeLabel(EmailType type) {
        switch (type) {
            case DIGEST:             return "daily digest";
            case OVERDUE_REMINDER:   return "overdue reminder";
            case EXAM_REMINDER_7D:
            case EXAM_REMINDER_3D:
            case EXAM_REMINDER_1D:   return "exam reminder";
            case GOAL_REACHED:       return "goal-reached";
            case GOAL_MISSED:        return "goal-missed";
            default:                 return "";
        }
    }

    private String encouragementFor(int daysUntil) {
        if (daysUntil >= 7) return "A week out — plenty of time to front-load the hard topics.";
        if (daysUntil >= 3) return "Three days — a great window to nail the tricky sections.";
        return "Tomorrow. Deep breath — you've got this. 💪";
    }

    private String daysOverduePhrase(LocalDate dueDate, LocalDate today) {
        if (dueDate == null) return "overdue";
        long n = java.time.temporal.ChronoUnit.DAYS.between(dueDate, today);
        if (n <= 0)       return "due today";
        if (n == 1)       return "1 day overdue";
        return n + " days overdue";
    }

    private String courseSuffix(String course) {
        return (course != null && !course.isBlank()) ? " (" + esc(course) + ")" : "";
    }
    private String courseSuffixPlain(String course) {
        return (course != null && !course.isBlank()) ? " (" + course + ")" : "";
    }

    private static String firstName(User user) {
        String name = user.getUsername();
        return (name != null && !name.isBlank()) ? name : "there";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String s(int n) { return n == 1 ? "" : "s"; }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "http://localhost:8080";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
