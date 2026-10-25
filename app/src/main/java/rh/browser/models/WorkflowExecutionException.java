package rh.browser.models;

public class WorkflowExecutionException extends Exception {
    public final String sourceFile;
    public final int lineNumber;
    public final String details;
    public WorkflowExecutionException(String message, String sourceFile, int lineNumber, String details, Throwable cause) {
        super(message, cause);
        this.sourceFile = sourceFile == null ? "" : sourceFile;
        this.lineNumber = lineNumber;
        this.details = details;
    }
    public WorkflowExecutionException(String message, String sourceFile, int lineNumber, String details) {
        this(message, sourceFile, lineNumber, details, null);
    }
    public WorkflowExecutionException(String message, String sourceFile, int lineNumber) {
        this(message, sourceFile, lineNumber, null, null);
    }
}
