package StudySyncer.dto;

public class TrackerSessionDto {

    private final Long    id;
    private final String  date;          // yyyy-MM-dd
    private final String  time;          // HH:mm
    private final String  materialName;
    private final int     durationMinutes;
    private final String  timerMode;
    private final boolean completed;

    public TrackerSessionDto(Long id, String date, String time,
                              String materialName, int durationMinutes,
                              String timerMode, boolean completed) {
        this.id              = id;
        this.date            = date;
        this.time            = time;
        this.materialName    = materialName;
        this.durationMinutes = durationMinutes;
        this.timerMode       = timerMode;
        this.completed       = completed;
    }

    public Long    getId()              { return id; }
    public String  getDate()            { return date; }
    public String  getTime()            { return time; }
    public String  getMaterialName()    { return materialName; }
    public int     getDurationMinutes() { return durationMinutes; }
    public String  getTimerMode()       { return timerMode; }
    public boolean isCompleted()        { return completed; }
}
