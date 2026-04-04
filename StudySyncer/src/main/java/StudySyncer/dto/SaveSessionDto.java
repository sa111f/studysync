package StudySyncer.dto;

/** Payload sent by the frontend when a study timer session completes. */
public class SaveSessionDto {

    private String  materialName;
    private int     durationMinutes;
    private String  timerMode;
    private boolean completed;

    public String  getMaterialName()    { return materialName; }
    public int     getDurationMinutes() { return durationMinutes; }
    public String  getTimerMode()       { return timerMode; }
    public boolean isCompleted()        { return completed; }

    public void setMaterialName(String n)    { this.materialName = n; }
    public void setDurationMinutes(int d)    { this.durationMinutes = d; }
    public void setTimerMode(String m)       { this.timerMode = m; }
    public void setCompleted(boolean c)      { this.completed = c; }
}
