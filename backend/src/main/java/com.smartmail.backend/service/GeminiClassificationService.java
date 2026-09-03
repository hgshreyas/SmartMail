package com.smartmail.backend.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

@Service
public class GeminiClassificationService {

    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final String OLLAMA_MODEL = "llama3.2:3b";

    // Keep the prompt reasonably small so Ollama can respond faster.
    private static final int MAX_BODY_LENGTH = 2000;

    private final HttpClient httpClient;

    public GeminiClassificationService() {

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String classify(
            String sender,
            String domain,
            String subject,
            String body,
            boolean hasListUnsubscribe,
            boolean hasListUnsubscribePost,
            boolean bulkMail,
            boolean automatedSender) {

        String prompt = buildPrompt(
                sender,
                domain,
                subject,
                body,
                hasListUnsubscribe,
                hasListUnsubscribePost,
                bulkMail,
                automatedSender
        );

        try {

            String result = callOllama(prompt).join();

            if (result != null) {
                System.out.println(
                        "SmartMail Ollama response received."
                );
            }

            return result;

        } catch (Exception e) {

            System.out.println(
                    "SmartMail Ollama error: " + e.getMessage()
            );

            return null;
        }
    }

    public CompletableFuture<String> classifyAsync(
            String sender,
            String domain,
            String subject,
            String body,
            boolean hasListUnsubscribe,
            boolean hasListUnsubscribePost,
            boolean bulkMail,
            boolean automatedSender) {

        String prompt = buildPrompt(
                sender,
                domain,
                subject,
                body,
                hasListUnsubscribe,
                hasListUnsubscribePost,
                bulkMail,
                automatedSender
        );

        return callOllama(prompt);
    }

    private CompletableFuture<String> callOllama(String prompt) {

        return CompletableFuture.supplyAsync(() -> {

            try {

                String escapedPrompt = escapeJson(prompt);

                String requestBody =
                        "{"
                                + "\"model\":\"" + OLLAMA_MODEL + "\","
                                + "\"prompt\":\"" + escapedPrompt + "\","
                                + "\"stream\":false,"
                                + "\"format\":\"json\","
                                + "\"keep_alive\":\"10m\","
                                + "\"options\":{"
                                + "\"temperature\":0,"
                                + "\"num_predict\":64"
                                + "}"
                                + "}";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(OLLAMA_URL))
                        .timeout(Duration.ofSeconds(180))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response =
                        httpClient.send(
                                request,
                                HttpResponse.BodyHandlers.ofString()
                        );

                if (response.statusCode() != 200) {

                    System.out.println(
                            "SmartMail Ollama HTTP error: "
                                    + response.statusCode()
                    );

                    System.out.println(
                            response.body()
                    );

                    return null;
                }

                String responseBody = response.body();

                String generatedText =
                        extractResponse(responseBody);

                if (generatedText == null
                        || generatedText.isBlank()) {

                    System.out.println(
                            "SmartMail Ollama returned an empty response."
                    );

                    return null;
                }

                System.out.println(
                        "SmartMail Ollama response received."
                );

                return generatedText;

            } catch (Exception e) {

                System.out.println(
                        "SmartMail Ollama async error: "
                                + e.getMessage()
                );

                return null;
            }
        });
    }

    private String buildPrompt(
            String sender,
            String domain,
            String subject,
            String body,
            boolean hasListUnsubscribe,
            boolean hasListUnsubscribePost,
            boolean bulkMail,
            boolean automatedSender) {

        String safeBody = limitBody(body);

        return """
                You are an email classification system.

                Classify the email into EXACTLY ONE category:

                IMPORTANT
                PROMOTIONAL
                SPAM

                IMPORTANT:
                - Bank transactions
                - Payment confirmations
                - Security alerts
                - OTPs
                - Account alerts
                - Job opportunities
                - Interviews
                - Internships
                - Academic deadlines
                - Important work messages
                - Financial statements or investment transactions

                PROMOTIONAL:
                - Marketing emails
                - Discounts
                - Offers
                - Coupons
                - Newsletters
                - Shopping promotions
                - Surveys
                - Entertainment promotions

                SPAM:
                - Fake lottery winnings
                - Fake prizes
                - Fraudulent money offers
                - Suspicious scams
                - Phishing
                - Clearly malicious messages

                RULES:

                1. Return ONLY valid JSON.
                2. Do not write anything outside the JSON.
                3. category MUST be IMPORTANT, PROMOTIONAL, or SPAM.
                4. confidence MUST be a number from 0.0 to 1.0.
                5. reason must be short.
                6. Do not create any other category.

                Email:

                Sender: %s
                Domain: %s
                Subject: %s

                Gmail signals:

                List-Unsubscribe: %s
                List-Unsubscribe-Post: %s
                Bulk mail: %s
                Automated sender: %s

                Body:

                %s

                Return exactly:

                {
                  "category": "IMPORTANT",
                  "confidence": 0.95,
                  "reason": "Brief reason"
                }
                """.formatted(
                sender,
                domain,
                subject,
                hasListUnsubscribe,
                hasListUnsubscribePost,
                bulkMail,
                automatedSender,
                safeBody
        );
    }

    private String limitBody(String body) {

        if (body == null || body.isBlank()) {
            return "";
        }

        if (body.length() <= MAX_BODY_LENGTH) {
            return body;
        }

        System.out.println(
                "SmartMail: Email body truncated for Ollama from "
                        + body.length()
                        + " to "
                        + MAX_BODY_LENGTH
                        + " characters."
        );

        return body.substring(0, MAX_BODY_LENGTH);
    }

    private String extractResponse(String json) {

        if (json == null || json.isBlank()) {
            return null;
        }

        int responseIndex = json.indexOf("\"response\"");

        if (responseIndex == -1) {
            return null;
        }

        int colonIndex = json.indexOf(
                ':',
                responseIndex
        );

        if (colonIndex == -1) {
            return null;
        }

        int firstQuote = json.indexOf(
                '"',
                colonIndex + 1
        );

        if (firstQuote == -1) {
            return null;
        }

        StringBuilder result = new StringBuilder();

        boolean escaped = false;

        for (int i = firstQuote + 1;
             i < json.length();
             i++) {

            char c = json.charAt(i);

            if (escaped) {

                if (c == 'n') {
                    result.append('\n');

                } else if (c == 'r') {
                    result.append('\r');

                } else if (c == 't') {
                    result.append('\t');

                } else if (c == '"') {
                    result.append('"');

                } else if (c == '\\') {
                    result.append('\\');

                } else {

                    result.append(c);
                }

                escaped = false;

            } else if (c == '\\') {

                escaped = true;

            } else if (c == '"') {

                break;

            } else {

                result.append(c);
            }
        }

        return result.toString().trim();
    }

    private String escapeJson(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}

