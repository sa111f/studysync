package StudySyncer.dto;

public class TrackerMaterialDto {

    private final String name;
    private final int    minutes;

    public TrackerMaterialDto(String name, int minutes) {
        this.name    = name;
        this.minutes = minutes;
    }

    public String getName()    { return name; }
    public int    getMinutes() { return minutes; }
}
