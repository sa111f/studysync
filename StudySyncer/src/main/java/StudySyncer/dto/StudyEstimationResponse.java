package StudySyncer.dto;

public class StudyEstimationResponse {
    private int minMinutes;
    private int recommendedMinutes;
    private int maxMinutes;
    private String rationale;
    private String estimateSource;

    public StudyEstimationResponse() {}

    public StudyEstimationResponse(int minMinutes, int recommendedMinutes,
                                   int maxMinutes, String rationale, String estimateSource) {
        this.minMinutes         = minMinutes;
        this.recommendedMinutes = recommendedMinutes;
        this.maxMinutes         = maxMinutes;
        this.rationale          = rationale;
        this.estimateSource     = estimateSource;
    }

    public int    getMinMinutes()         { return minMinutes; }
    public int    getRecommendedMinutes() { return recommendedMinutes; }
    public int    getMaxMinutes()         { return maxMinutes; }
    public String getRationale()          { return rationale; }
    public String getEstimateSource()     { return estimateSource; }

    public void setMinMinutes(int v)         { this.minMinutes = v; }
    public void setRecommendedMinutes(int v) { this.recommendedMinutes = v; }
    public void setMaxMinutes(int v)         { this.maxMinutes = v; }
    public void setRationale(String v)       { this.rationale = v; }
    public void setEstimateSource(String v)  { this.estimateSource = v; }
}
