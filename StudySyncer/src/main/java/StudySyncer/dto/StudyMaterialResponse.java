package StudySyncer.dto;

/** STUD-71: Response DTO for POST /api/study-material/estimate */
public class StudyMaterialResponse {

    private int    lightMinutes;
    private int    standardMinutes;
    private int    deepMinutes;
    private String reason;
    private String estimateSource;
    private int    wordCount;   // exposed for UI debugging
    private int    pageCount;   // exposed for UI debugging

    public StudyMaterialResponse() {}

    public StudyMaterialResponse(int lightMinutes, int standardMinutes,
                                 int deepMinutes, String reason, String estimateSource,
                                 int wordCount, int pageCount) {
        this.lightMinutes    = lightMinutes;
        this.standardMinutes = standardMinutes;
        this.deepMinutes     = deepMinutes;
        this.reason          = reason;
        this.estimateSource  = estimateSource;
        this.wordCount       = wordCount;
        this.pageCount       = pageCount;
    }

    public int    getLightMinutes()    { return lightMinutes; }
    public int    getStandardMinutes() { return standardMinutes; }
    public int    getDeepMinutes()     { return deepMinutes; }
    public String getReason()          { return reason; }
    public String getEstimateSource()  { return estimateSource; }
    public int    getWordCount()       { return wordCount; }
    public int    getPageCount()       { return pageCount; }

    public void setLightMinutes(int v)      { this.lightMinutes    = v; }
    public void setStandardMinutes(int v)   { this.standardMinutes = v; }
    public void setDeepMinutes(int v)       { this.deepMinutes     = v; }
    public void setReason(String v)         { this.reason          = v; }
    public void setEstimateSource(String v) { this.estimateSource  = v; }
    public void setWordCount(int v)         { this.wordCount       = v; }
    public void setPageCount(int v)         { this.pageCount       = v; }
}
