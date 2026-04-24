package StudySyncer.dto;

import StudySyncer.entity.User;

/**
 * Plain POJO echoing the user's notification preferences (Phase 8).
 *
 * Times are serialised as "HH:mm" strings rather than {@code LocalTime}
 * so the frontend's {@code <input type="time">} binds cleanly. The
 * controller parses them back to {@code LocalTime} on save.
 */
public class NotificationPreferencesDto {

    private boolean digestEnabled;
    private String  digestLocalTime;        // "HH:mm"

    private boolean overdueReminderEnabled;
    private String  overdueReminderLocalTime;

    private boolean examReminderEnabled;

    public NotificationPreferencesDto() { /* Jackson */ }

    public static NotificationPreferencesDto from(User user) {
        NotificationPreferencesDto dto = new NotificationPreferencesDto();
        dto.digestEnabled            = user.isDigestEnabled();
        dto.digestLocalTime          = user.getDigestLocalTime() != null
                                     ? user.getDigestLocalTime().toString().substring(0, 5)
                                     : "08:00";
        dto.overdueReminderEnabled   = user.isOverdueReminderEnabled();
        dto.overdueReminderLocalTime = user.getOverdueReminderLocalTime() != null
                                     ? user.getOverdueReminderLocalTime().toString().substring(0, 5)
                                     : "20:00";
        dto.examReminderEnabled      = user.isExamReminderEnabled();
        return dto;
    }

    public boolean isDigestEnabled()                     { return digestEnabled; }
    public String  getDigestLocalTime()                  { return digestLocalTime; }
    public boolean isOverdueReminderEnabled()            { return overdueReminderEnabled; }
    public String  getOverdueReminderLocalTime()         { return overdueReminderLocalTime; }
    public boolean isExamReminderEnabled()               { return examReminderEnabled; }

    public void setDigestEnabled(boolean b)              { this.digestEnabled = b; }
    public void setDigestLocalTime(String t)             { this.digestLocalTime = t; }
    public void setOverdueReminderEnabled(boolean b)     { this.overdueReminderEnabled = b; }
    public void setOverdueReminderLocalTime(String t)    { this.overdueReminderLocalTime = t; }
    public void setExamReminderEnabled(boolean b)        { this.examReminderEnabled = b; }
}
