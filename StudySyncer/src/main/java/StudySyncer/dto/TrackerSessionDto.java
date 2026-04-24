package StudySyncer.dto;

public class TrackerSessionDto {

    private final Long    id;
    private final String  date;          // yyyy-MM-dd
    private final String  time;          // HH:mm
    private final String  materialName;
    private final int     durationMinutes;  // actual studied
    private final int     plannedMinutes;
    private final int     overtimeMinutes;
    private final String  timerMode;
    private final boolean completed;

    /**
     * Soft reference to the Task this session was logged against (null for
     * generic sessions). Populated from StudySession.taskId.
     */
    private final Long    taskId;

    /**
     * Display title of the referenced Task at read time. Null when:
     *   - taskId is null (generic session), or
     *   - the referenced task has been deleted since the session was logged
     *     (soft-reference semantics — the session row survives task deletion).
     */
    private final String  taskTitle;

    public TrackerSessionDto(Long id, String date, String time,
                              String materialName, int durationMinutes,
                              int plannedMinutes, int overtimeMinutes,
                              String timerMode, boolean completed,
                              Long taskId, String taskTitle) {
        this.id              = id;
        this.date            = date;
        this.time            = time;
        this.materialName    = materialName;
        this.durationMinutes = durationMinutes;
        this.plannedMinutes  = plannedMinutes;
        this.overtimeMinutes = overtimeMinutes;
        this.timerMode       = timerMode;
        this.completed       = completed;
        this.taskId          = taskId;
        this.taskTitle       = taskTitle;
    }

    public Long    getId()              { return id; }
    public String  getDate()            { return date; }
    public String  getTime()            { return time; }
    public String  getMaterialName()    { return materialName; }
    public int     getDurationMinutes() { return durationMinutes; }
    public int     getPlannedMinutes()  { return plannedMinutes; }
    public int     getOvertimeMinutes() { return overtimeMinutes; }
    public String  getTimerMode()       { return timerMode; }
    public boolean isCompleted()        { return completed; }
    public Long    getTaskId()          { return taskId; }
    public String  getTaskTitle()       { return taskTitle; }
}
