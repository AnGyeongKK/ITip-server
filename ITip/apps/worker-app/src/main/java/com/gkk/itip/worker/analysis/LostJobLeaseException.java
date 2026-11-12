package com.gkk.itip.worker.analysis;

public class LostJobLeaseException extends IllegalStateException {
    public LostJobLeaseException() { super("worker no longer owns this job lease"); }
}
