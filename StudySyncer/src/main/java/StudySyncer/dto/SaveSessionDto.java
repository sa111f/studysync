package StudySyncer.dto;

/**
 * Payload sent by the frontend when a study timer session is finalized.
 *
 * durationMinutes is the ACTUAL time studied (may exceed plannedMinutes
 * when the user studied past the planned duration in overtime mode).
 * plannedMinutes and overtimeMinutes are optional — older clients may
 * omit them and the server defaults to planned=duration, overtime=0.
 */
public class SaveSessionDto {

    private String  materialName;
    private int     durationMinutes;
    private Integer plannedMinutes;
    private Integer overtimeMinutes;
    private String  timerMode;
    private boolean completed;

    /**
     * Optional soft reference to the Task the session was logged against.
     * When non-null the server validates ownership before saving (400 if it
     * doesn't belong to the current user). When null the session is a generic
     * one — same behaviour as the pre-Phase-3 pipeline.
     */
    private Long    taskId;

    public String  getMaterialName()    { return materialName; }
    public int     getDurationMinutes() { return durationMinutes; }
    public Integer getPlannedMinutes()  { return plannedMinutes; }
    public Integer getOvertimeMinutes() { return overtimeMinutes; }
    public String  getTimerMode()       { return timerMode; }
    public boolean isCompleted()        { return completed; }
    public Long    getTaskId()          { return taskId; }

    public void setMaterialName(String n)       { this.materialName = n; }
    public void setDurationMinutes(int d)       { this.durationMinutes = d; }
    public void setPlannedMinutes(Integer m)    { this.plannedMinutes = m; }
    public void setOvertimeMinutes(Integer m)   { this.overtimeMinutes = m; }
    public void setTimerMode(String m)          { this.timerMode = m; }
    public void setCompleted(boolean c)         { this.completed = c; }
    public void setTaskId(Long taskId)          { this.taskId = taskId; }
}
