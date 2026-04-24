package StudySyncer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Request body for POST /api/exams and PUT /api/exams/{id}.
 *
 * DATETIME HANDLING (hybrid — documented in the Phase 6 final report):
 *   The exam entity's column is an Instant (UTC). This request accepts
 *   datetime in EITHER of two shapes, chosen by whichever client path
 *   is easiest:
 *
 *     A) `dateTime`: pre-serialized ISO-8601 with offset
 *        (e.g. "2026-06-15T14:00:00-04:00"). Used by the AI exam parser
 *        response path, which already returns a fully-resolved Instant.
 *
 *     B) `date` + `time` + `timezone` as separate strings
 *        (e.g. date="2026-06-15", time="14:00", timezone="America/Toronto").
 *        Used by the manual create / edit form on /exams, which avoids
 *        client-side timezone arithmetic gotchas (Intl offset quirks across
 *        DST boundaries, Safari's ISO parsing differences, etc.).
 *
 *   The controller validates that AT LEAST one of the two paths supplies a
 *   parseable datetime; missing / unparseable input returns 400.
 */
public class ExamRequest {

    @NotBlank(message = "Title is required.")
    @Size(max = 200, message = "Title must be at most 200 characters.")
    private String title;

    @Size(max = 100, message = "Course must be at most 100 characters.")
    private String course;

    /** Shape A — pre-serialized instant. Nullable; use shape B if null. */
    private Instant dateTime;

    /** Shape B — local date in ISO format (yyyy-MM-dd). Nullable. */
    private String date;

    /** Shape B — local time (HH:mm or HH:mm:ss). Nullable. */
    private String time;

    /** Shape B — IANA timezone id (e.g. "America/Toronto"). Nullable. */
    private String timezone;

    @Size(max = 2000, message = "Material must be at most 2000 characters.")
    private String material;

    @Size(max = 1000, message = "Notes must be at most 1000 characters.")
    private String notes;

    @Size(max = 200, message = "Location must be at most 200 characters.")
    private String location;

    // ── Getters ───────────────────────────────────────────
    public String  getTitle()    { return title; }
    public String  getCourse()   { return course; }
    public Instant getDateTime() { return dateTime; }
    public String  getDate()     { return date; }
    public String  getTime()     { return time; }
    public String  getTimezone() { return timezone; }
    public String  getMaterial() { return material; }
    public String  getNotes()    { return notes; }
    public String  getLocation() { return location; }

    // ── Setters ───────────────────────────────────────────
    public void setTitle(String t)    { this.title = t; }
    public void setCourse(String c)   { this.course = c; }
    public void setDateTime(Instant d){ this.dateTime = d; }
    public void setDate(String d)     { this.date = d; }
    public void setTime(String t)     { this.time = t; }
    public void setTimezone(String t) { this.timezone = t; }
    public void setMaterial(String m) { this.material = m; }
    public void setNotes(String n)    { this.notes = n; }
    public void setLocation(String l) { this.location = l; }
}
