package org.casemgmt.engine;

public class EngineException extends RuntimeException {
    public EngineException(String message, Throwable cause) { super(message, cause); }
    public EngineException(String message) { super(message); }
}
