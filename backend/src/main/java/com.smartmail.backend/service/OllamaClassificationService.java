package com.smartmail.backend.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

@Service
public class OllamaClassificationService {

    private static final String OLLAMA_URL =
            "http://localhost:11434/api/generate";

    private static final String OLLAMA_MODEL =
            "llama3.2:1b";

    // Keep the prompt reasonably small so Ollama can respond faster.
    private static final int MAX_BODY_LENGTH = 1000;

    /*
     * Dedicated executor for Ollama requests.
     *
     * IMPORTANT:
     *
     * Ollama calls are blocking HTTP operations.
     *
     * We therefore DO NOT use Java's common ForkJoinPool.
     *
     * Only 1 Ollama request is allowed to run at a time.
     * Remaining requests wait safely in the executor queue.
     * This keeps local AI load low on lightweight hardware.
     */
    private final ExecutorService ollamaExecutor =
            Executors.newFixedThreadPool(
                    1,
                    runnable -> {

                        Thread thread =
                                new Thread(
                                        runnable,
                                        "smartmail-ollama-worker"
                                );

                        thread.setDaemon(true);

                        return thread;
                    }
            );

    private final HttpClient httpClient;

    public OllamaClassificationService() {

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

            String result =
                    callOllama(prompt).join();

            if (result != null) {

                System.out.println(
                        "SmartMail Ollama response received."
                );
            }

            return result;

        } catch (Exception e) {

            System.out.println(
                    "SmartMail Ollama error: " +
                            e.getMessage()
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

    private CompletableFuture<String> callOllama(
            String prompt) {

        return CompletableFuture.supplyAsync(
                () -> {

                    long startTime =
                            System.currentTimeMillis();

                    try {

                        String escapedPrompt =
                                escapeJson(prompt);

                        String requestBody =
                                "{"
                                        + "\"model\":\""
                                        + OLLAMA_MODEL
                                        + "\","
                                        + "\"prompt\":\""
                                        + escapedPrompt
                                        + "\","
                                        + "\"stream\":false,"
                                        + "\"format\":{"
                                        + "\"type\":\"object\","
                                        + "\"properties\":{"
                                        + "\"category\":{"
                                        + "\"type\":\"string\","
                                        + "\"enum\":[\"IMPORTANT\",\"PROMOTIONAL\",\"SPAM\"]"
                                        + "},"
                                        + "\"confidence\":{"
                                        + "\"type\":\"number\","
                                        + "\"minimum\":0,"
                                        + "\"maximum\":1"
                                        + "},"
                                        + "\"reason\":{"
                                        + "\"type\":\"string\""
                                        + "}"
                                        + "},"
                                        + "\"required\":[\"category\",\"confidence\",\"reason\"],"
                                        + "\"additionalProperties\":false"
                                        + "},"
                                        + "\"keep_alive\":\"1m\","
                                        + "\"options\":{"
                                        + "\"temperature\":0,"
                                        + "\"num_ctx\":2048,"
                                        + "\"num_predict\":96"
                                        + "}"
                                        + "}";

                        HttpRequest request =
                                HttpRequest.newBuilder()
                                        .uri(
                                                URI.create(
                                                        OLLAMA_URL
                                                )
                                        )
                                        .timeout(
                                                Duration.ofSeconds(180)
                                        )
                                        .header(
                                                "Content-Type",
                                                "application/json"
                                        )
                                        .POST(
                                                HttpRequest.BodyPublishers
                                                        .ofString(
                                                                requestBody
                                                        )
                                        )
                                        .build();

                        System.out.println(
                                "SmartMail: Sending request to Ollama..."
                        );

                        HttpResponse<String> response =
                                httpClient.send(
                                        request,
                                        HttpResponse.BodyHandlers
                                                .ofString()
                                );

                        long elapsed =
                                System.currentTimeMillis()
                                        - startTime;

                        System.out.println(
                                "SmartMail: Ollama request completed in "
                                        + elapsed
                                        + " ms."
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

                        String responseBody =
                                response.body();

                        String generatedText =
                                extractResponse(
                                        responseBody
                                );

                        if (generatedText == null ||
                                generatedText.isBlank()) {

                            System.out.println(
                                    "SmartMail Ollama returned " +
                                            "an empty response."
                            );

                            return null;
                        }

                        System.out.println(
                                "SmartMail Ollama response received."
                        );

                        return generatedText;

                    } catch (Exception e) {

                        long elapsed =
                                System.currentTimeMillis()
                                        - startTime;

                        System.out.println(
                                "SmartMail Ollama request failed " +
                                        "after "
                                        + elapsed
                                        + " ms: "
                                        + e.getMessage()
                        );

                        return null;
                    }
                },
                ollamaExecutor
        );
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

        String safeBody =
                limitBody(body);

        return """
                Classify this email into exactly one category:
                IMPORTANT, PROMOTIONAL, or SPAM.

                IMPORTANT:
                Personal transactions, banking/payment activity, security alerts,
                OTP/account events, real job/interview/application updates,
                academic deadlines, or important work communication.

                PROMOTIONAL:
                Ads, sales, discounts, offers, marketing newsletters, surveys,
                product promotions, engagement campaigns, or general job listings.

                SPAM:
                Clear phishing, scams, fake prizes/lotteries, credential theft,
                malicious impersonation, or obviously fraudulent messages.

                Rules:
                - Judge mainly from subject and body.
                - Automated/bulk/unsubscribe signals are supporting evidence only.
                - A real bank/transaction/security event is IMPORTANT even if automated.
                - A legitimate marketing email is PROMOTIONAL, not SPAM.
                - If uncertain, choose the best-supported category with lower confidence.

                Metadata:
                listUnsubscribe=%s
                listUnsubscribePost=%s
                bulkMail=%s
                automatedSender=%s

                Sender: %s
                Domain: %s
                Subject: %s
                Body:
                %s

                Return exactly these fields:
                category, confidence, reason.
                Confidence must be a number from 0.0 to 1.0.
                """.formatted(
                hasListUnsubscribe,
                hasListUnsubscribePost,
                bulkMail,
                automatedSender,
                sender,
                domain,
                subject,
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

        return body.substring(
                0,
                MAX_BODY_LENGTH
        );
    }

    private String extractResponse(String json) {

        if (json == null || json.isBlank()) {

            return null;
        }

        int responseIndex =
                json.indexOf("\"response\"");

        if (responseIndex == -1) {

            return null;
        }

        int colonIndex =
                json.indexOf(
                        ':',
                        responseIndex
                );

        if (colonIndex == -1) {

            return null;
        }

        int firstQuote =
                json.indexOf(
                        '"',
                        colonIndex + 1
                );

        if (firstQuote == -1) {

            return null;
        }

        StringBuilder result =
                new StringBuilder();

        boolean escaped = false;

        for (int i = firstQuote + 1;
             i < json.length();
             i++) {

            char c =
                    json.charAt(i);

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

        StringBuilder escaped =
                new StringBuilder(
                        value.length() + 32
                );

        for (int i = 0;
             i < value.length();
             i++) {

            char c =
                    value.charAt(i);

            switch (c) {

                case '\\' ->
                        escaped.append("\\\\");

                case '"' ->
                        escaped.append("\\\"");

                case '\b' ->
                        escaped.append("\\b");

                case '\f' ->
                        escaped.append("\\f");

                case '\n' ->
                        escaped.append("\\n");

                case '\r' ->
                        escaped.append("\\r");

                case '\t' ->
                        escaped.append("\\t");

                default -> {

                    /*
                     * JSON does not allow raw control characters
                     * U+0000 through U+001F inside a string.
                     *
                     * Gmail bodies can occasionally contain one of
                     * these characters, so encode any remaining
                     * control character using a Unicode escape.
                     */
                    if (c < 0x20) {

                        escaped.append(
                                String.format(
                                        "\\u%04x",
                                        (int) c
                                )
                        );

                    } else {

                        escaped.append(c);
                    }
                }
            }
        }

        return escaped.toString();
    }
}