package org.noear.solon.ai.harness.session.steer;

public final class SteerResult {
    private final SteerStatus status;
    private final String steerId;
    private final int queued;

    public SteerResult(SteerStatus status, String steerId, int queued) {
        this.status = status;
        this.steerId = steerId;
        this.queued = queued;
    }

    public SteerStatus getStatus() { return status; }
    public String getStatusCode() { return status.code(); }
    public String getSteerId() { return steerId; }
    public int getQueued() { return queued; }
    public boolean isSteered() { return status == SteerStatus.STEERED; }
    public boolean isBoxFull() { return status == SteerStatus.BOX_FULL; }
    public boolean isNotRunning() {
        return status == SteerStatus.NOT_RUNNING || status == SteerStatus.TURN_CHANGED;
    }
}