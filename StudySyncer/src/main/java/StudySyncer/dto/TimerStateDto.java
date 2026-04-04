package StudySyncer.dto;

public class TimerStateDto {
    private String mode;
    private int totalSeconds;
    private int sessionCount;

    public String getMode()             { return mode; }
    public int getTotalSeconds()        { return totalSeconds; }
    public int getSessionCount()        { return sessionCount; }
    public void setMode(String mode)    { this.mode = mode; }
    public void setTotalSeconds(int s)  { this.totalSeconds = s; }
    public void setSessionCount(int c)  { this.sessionCount = c; }
}
