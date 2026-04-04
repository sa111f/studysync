package StudySyncer.dto;

/** STUD-69: Request DTO for POST /api/study-material/estimate */
public class StudyMaterialRequest {

    private String title;        // optional
    private String content;      // required — pasted notes or extracted PDF text
    private int    pageCount;    // optional — number of PDF pages or slides (0 = unknown)
    private String documentType; // optional — e.g. "lecture slides", "notes", "PDF"

    public String getTitle()        { return title; }
    public String getContent()      { return content; }
    public int    getPageCount()    { return pageCount; }
    public String getDocumentType() { return documentType; }

    public void setTitle(String title)           { this.title        = title; }
    public void setContent(String content)       { this.content      = content; }
    public void setPageCount(int pageCount)      { this.pageCount    = pageCount; }
    public void setDocumentType(String docType)  { this.documentType = docType; }
}
