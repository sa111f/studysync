package StudySyncer.dto;

public class TrackerSummaryDto {

    private int    totalMinutes;
    private int    daysAccessed;
    private int    streak;
    private String periodLabel;

    public int    getTotalMinutes()  { return totalMinutes; }
    public int    getDaysAccessed()  { return daysAccessed; }
    public int    getStreak()        { return streak; }
    public String getPeriodLabel()   { return periodLabel; }

    public void setTotalMinutes(int m)    { this.totalMinutes = m; }
    public void setDaysAccessed(int d)    { this.daysAccessed = d; }
    public void setStreak(int s)          { this.streak = s; }
    public void setPeriodLabel(String l)  { this.periodLabel = l; }
}
