package com.nettgryppa.SlackLatexRenderer;

import java.util.HashMap;
import java.util.Map;

public final class ChallengeResponse {
    private int statusCode;
    private String body;
    private Map<String, String> headers;

    public ChallengeResponse(String challenge) {
        body = "{ \"challenge\": \"" + challenge + "\"}";
        statusCode = 200;
        headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
    }

    public String getBody() {
        return body;
    }
    public void setBody(String body) {
        this.body = body;
    }
    public int getStatusCode() {
        return statusCode;
    }
    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }
    public Map<String, String> getHeaders() {
        return headers;
    }
    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }
}