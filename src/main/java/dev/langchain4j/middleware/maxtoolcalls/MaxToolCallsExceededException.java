package dev.langchain4j.middleware.maxtoolcalls;

/** Thrown when {@link MaxToolCallsMiddleware} is configured with {@link MaxToolCallsMiddleware.OnLimit#THROW} and the cap is exceeded. */
public final class MaxToolCallsExceededException extends RuntimeException {

    private final int maxCalls;
    private final int callNumber;
    private final String toolName;

    public MaxToolCallsExceededException(int maxCalls, int callNumber, String toolName) {
        super(
                "MaxToolCallsMiddleware: tool call limit of "
                        + maxCalls
                        + " exceeded (call "
                        + callNumber
                        + ", tool '"
                        + (toolName == null || toolName.isBlank() ? "unknown" : toolName)
                        + "').");
        this.maxCalls = maxCalls;
        this.callNumber = callNumber;
        this.toolName = toolName == null ? "" : toolName;
    }

    public int maxCalls() {
        return maxCalls;
    }

    public int callNumber() {
        return callNumber;
    }

    public String toolName() {
        return toolName;
    }
}
