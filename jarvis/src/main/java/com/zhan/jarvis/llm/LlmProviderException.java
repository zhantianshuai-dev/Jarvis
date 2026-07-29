package com.zhan.jarvis.llm;

public class LlmProviderException extends RuntimeException {

    private final String provider;
    private final int statusCode;
    private final String responseBody;

    public LlmProviderException(String provider, int statusCode, String responseBody) {
        super("LLM provider " + provider + " failed: status=" + statusCode + ", body=" + responseBody);
        this.provider = provider;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public LlmProviderException(String provider, String message, Throwable cause) {
        super("LLM provider " + provider + " failed: " + message, cause);
        this.provider = provider;
        this.statusCode = 0;
        this.responseBody = "";
    }

    public String provider() {
        return provider;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
