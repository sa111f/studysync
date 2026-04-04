package StudySyncer.dto;

import java.util.List;

public class TrackerChartDto {

    private List<String>  labels;
    private List<Integer> values;   // minutes per label
    private int           totalMinutes;
    private String        periodLabel;

    public List<String>  getLabels()       { return labels; }
    public List<Integer> getValues()       { return values; }
    public int           getTotalMinutes() { return totalMinutes; }
    public String        getPeriodLabel()  { return periodLabel; }

    public void setLabels(List<String> l)    { this.labels = l; }
    public void setValues(List<Integer> v)   { this.values = v; }
    public void setTotalMinutes(int t)       { this.totalMinutes = t; }
    public void setPeriodLabel(String p)     { this.periodLabel = p; }
}
