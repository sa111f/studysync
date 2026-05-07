package StudySyncer.dto;

import StudySyncer.entity.TaskStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for PATCH /api/tasks/{id}/status.
 * Single field so a UI toggle only sends the one thing that changed.
 */
public class StatusPatchRequest {

    @NotNull(message = "Status is required.")
    private TaskStatus status;

    public TaskStatus getStatus()           { return status; }
    public void setStatus(TaskStatus s)     { this.status = s; }
}
