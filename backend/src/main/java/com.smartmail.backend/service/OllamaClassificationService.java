package com.smartmail.backend.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

@Service
public class OllamaClassificationService {

    // ============================================================
    // LOCAL OLLAMA CONFIGURATION
    // ============================================================

    private static final String OLLAMA_URL =
            "http://localhost:11434/api/generate";

    private static final String OLLAMA_MODEL =
            "llama3.2:1b";


    // ============================================================
    // CLOUD GEMINI CONFIGURATION
    // ============================================================

    private static final String GEMINI_API_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final String DEFAULT_GEMINI_MODEL =
            "gemini-3.8-flash";


    // Keep AI input reasonably small.
    private static final int MAX_BODY_LENGTH = 1000;


    /*
     * One AI request at a time.
     *
     * This is important locally because Ollama can be resource intensive.
     *
     * It is also useful in production because it prevents a large pending
     * backlog from sending hundreds of Gemini requests simultaneously.
     */
    private final ExecutorService aiExecutor =
            Executors.newFixedThreadPool(
                    1,
                    runnable -> {

                        Thread thread =
                                new Thread(
                                        runnable,
                                        "smartmail-ai-worker"
                                );

                        thread.setDaemon(true);

                        return thread;
                    }
            );


    private final HttpClient httpClient;

    private final String aiProvider;
    private final String geminiApiKey;
    private final String geminiModel;


    public OllamaClassificationService() {

        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(
                                Duration.ofSeconds(10)
                        )
                        .build();


        /*
         * LOCAL:
         *
         * AI_PROVIDER is normally absent, therefore SmartMail defaults
         * to Ollama exactly as before.
         *
         * RENDER:
         *
         * AI_PROVIDER=gemini
         *
         * Therefore the deployed application uses Gemini instead of
         * attempting to contact localhost:11434.
         */
        this.aiProvider =
                readEnvironmentVariable(
                        "AI_PROVIDER",
                        "ollama"
                )
                        .toLowerCase(
                                Locale.ROOT
                        );


        this.geminiApiKey =
                readEnvironmentVariable(
                        "GEMINI_API_KEY",
                        ""
                );


        this.geminiModel =
                readEnvironmentVariable(
                        "GEMINI_MODEL",
                        DEFAULT_GEMINI_MODEL
                );


        System.out.println(
                "SmartMail: AI provider = "
                        + aiProvider
        );


        if ("gemini".equals(aiProvider)) {

            System.out.println(
                    "SmartMail: Gemini model = "
                            + geminiModel
            );
        }
    }


    // ============================================================
    // SYNCHRONOUS CLASSIFICATION
    // ============================================================

    public String classify(
            String sender,
            String domain,
            String subject,
            String body,
            boolean hasListUnsubscribe,
            boolean hasListUnsubscribePost,
            boolean bulkMail,
            boolean automatedSender) {

        String prompt =
                buildPrompt(
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

            return callAi(
                    prompt
            ).join();

        } catch (Exception e) {

            System.err.println(
                    "SmartMail AI error: "
                            + e.getMessage()
            );

            return null;
        }
    }


    // ============================================================
    // ASYNCHRONOUS CLASSIFICATION
    // ============================================================

    public CompletableFuture<String> classifyAsync(
            String sender,
            String domain,
            String subject,
            String body,
            boolean hasListUnsubscribe,
            boolean hasListUnsubscribePost,
            boolean bulkMail,
            boolean automatedSender) {

        String prompt =
                buildPrompt(
                        sender,
                        domain,
                        subject,
                        body,
                        hasListUnsubscribe,
                        hasListUnsubscribePost,
                        bulkMail,
                        automatedSender
                );


        return callAi(
                prompt
        );
    }


    // ============================================================
    // SELECT AI PROVIDER
    // ============================================================

    private CompletableFuture<String> callAi(
            String prompt) {

        return CompletableFuture.supplyAsync(
                () -> {

                    if ("gemini".equals(
                            aiProvider
                    )) {

                        return callGemini(
                                prompt
                        );
                    }


                    /*
                     * Default provider remains Ollama.
                     *
                     * This means local development continues working
                     * without requiring any new local environment variable.
                     */
                    return callOllama(
                            prompt
                    );
                },
                aiExecutor
        );
    }


    // ============================================================
    // OLLAMA
    // ============================================================

    private String callOllama(
            String prompt) {

        long startTime =
                System.currentTimeMillis();


        try {

            String escapedPrompt =
                    escapeJson(
                            prompt
                    );


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
                                    Duration.ofSeconds(
                                            180
                                    )
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

                System.err.println(
                        "SmartMail Ollama HTTP error: "
                                + response.statusCode()
                );

                return null;
            }


            String generatedText =
                    extractJsonStringField(
                            response.body(),
                            "response"
                    );


            if (generatedText == null ||
                    generatedText.isBlank()) {

                System.err.println(
                        "SmartMail: Ollama returned an empty response."
                );

                return null;
            }


            return generatedText.trim();


        } catch (Exception e) {

            long elapsed =
                    System.currentTimeMillis()
                            - startTime;


            System.err.println(
                    "SmartMail: Ollama request failed after "
                            + elapsed
                            + " ms: "
                            + e.getMessage()
            );


            return null;
        }
    }


    // ============================================================
    // GEMINI
    // ============================================================

    private String callGemini(
            String prompt) {

        long startTime =
                System.currentTimeMillis();


        if (geminiApiKey == null ||
                geminiApiKey.isBlank()) {

            System.err.println(
                    "SmartMail: GEMINI_API_KEY is missing."
            );

            return null;
        }


        try {

            String escapedPrompt =
                    escapeJson(
                            prompt
                    );


            /*
             * responseMimeType requests JSON output.
             *
             * EmailClassifierService already validates the returned
             * category/confidence/reason structure, so both Ollama and
             * Gemini feed into exactly the same downstream logic.
             */
            String requestBody =
                    "{"
                            + "\"contents\":["
                            + "{"
                            + "\"role\":\"user\","
                            + "\"parts\":["
                            + "{"
                            + "\"text\":\""
                            + escapedPrompt
                            + "\""
                            + "}"
                            + "]"
                            + "}"
                            + "],"
                            + "\"generationConfig\":{"
                            + "\"temperature\":0,"
                            + "\"maxOutputTokens\":256,"
                            + "\"responseMimeType\":\"application/json\""
                            + "}"
                            + "}";


            String endpoint =
                    GEMINI_API_BASE
                            + geminiModel
                            + ":generateContent";


            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            endpoint
                                    )
                            )
                            .timeout(
                                    Duration.ofSeconds(
                                            180
                                    )
                            )
                            .header(
                                    "Content-Type",
                                    "application/json"
                            )
                            .header(
                                    "x-goog-api-key",
                                    geminiApiKey
                            )
                            .POST(
                                    HttpRequest.BodyPublishers
                                            .ofString(
                                                    requestBody
                                            )
                            )
                            .build();


            System.out.println(
                    "SmartMail: Sending request to Gemini..."
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
                    "SmartMail: Gemini request completed in "
                            + elapsed
                            + " ms."
            );


            if (response.statusCode() != 200) {

                System.err.println(
                        "SmartMail Gemini HTTP error: "
                                + response.statusCode()
                );


                /*
                 * Do not print the request or API key.
                 *
                 * The response body is useful for diagnosing issues such as
                 * invalid model names or quota problems.
                 */
                System.err.println(
                        response.body()
                );


                return null;
            }


            /*
             * Gemini generateContent returns:
             *
             * candidates
             *   -> content
             *      -> parts
             *         -> text
             *
             * Because this request is text-only, extracting the first
             * "text" field gives us the model's JSON classification.
             */
            String generatedText =
                    extractJsonStringField(
                            response.body(),
                            "text"
                    );


            if (generatedText == null ||
                    generatedText.isBlank()) {

                System.err.println(
                        "SmartMail: Gemini returned an empty response."
                );

                return null;
            }


            System.out.println(
                    "SmartMail: Gemini response received."
            );


            return generatedText.trim();


        } catch (Exception e) {

            long elapsed =
                    System.currentTimeMillis()
                            - startTime;


            System.err.println(
                    "SmartMail: Gemini request failed after "
                            + elapsed
                            + " ms: "
                            + e.getMessage()
            );


            return null;
        }
    }


    // ============================================================
    // PROMPT
    // ============================================================

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
                limitBody(
                        body
                );


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

                Return ONLY a JSON object with exactly these fields:

                {
                  "category": "IMPORTANT or PROMOTIONAL or SPAM",
                  "confidence": 0.0,
                  "reason": "short reason"
                }

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


    // ============================================================
    // BODY LIMIT
    // ============================================================

    private String limitBody(
            String body) {

        if (body == null ||
                body.isBlank()) {

            return "";
        }


        if (body.length() <=
                MAX_BODY_LENGTH) {

            return body;
        }


        System.out.println(
                "SmartMail: Email body truncated for AI from "
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


    // ============================================================
    // READ ENVIRONMENT VARIABLE
    // ============================================================

    private String readEnvironmentVariable(
            String key,
            String defaultValue) {

        String value =
                System.getenv(
                        key
                );


        if (value == null ||
                value.isBlank()) {

            return defaultValue;
        }


        return value.trim();
    }


    // ============================================================
    // EXTRACT JSON STRING FIELD
    // ============================================================

    private String extractJsonStringField(
            String json,
            String fieldName) {

        if (json == null ||
                json.isBlank() ||
                fieldName == null ||
                fieldName.isBlank()) {

            return null;
        }


        String field =
                "\""
                        + fieldName
                        + "\"";


        int fieldIndex =
                json.indexOf(
                        field
                );


        if (fieldIndex == -1) {

            return null;
        }


        int colonIndex =
                json.indexOf(
                        ':',
                        fieldIndex
                                + field.length()
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


        boolean escaped =
                false;


        for (int i = firstQuote + 1;
             i < json.length();
             i++) {


            char c =
                    json.charAt(
                            i
                    );


            if (escaped) {

                switch (c) {

                    case 'n' ->
                            result.append(
                                    '\n'
                            );

                    case 'r' ->
                            result.append(
                                    '\r'
                            );

                    case 't' ->
                            result.append(
                                    '\t'
                            );

                    case 'b' ->
                            result.append(
                                    '\b'
                            );

                    case 'f' ->
                            result.append(
                                    '\f'
                            );

                    case '"' ->
                            result.append(
                                    '"'
                            );

                    case '\\' ->
                            result.append(
                                    '\\'
                            );

                    case '/' ->
                            result.append(
                                    '/'
                            );

                    case 'u' -> {

                        /*
                         * Decode JSON Unicode escapes.
                         */
                        if (i + 4 <
                                json.length()) {

                            String hex =
                                    json.substring(
                                            i + 1,
                                            i + 5
                                    );


                            try {

                                int codePoint =
                                        Integer.parseInt(
                                                hex,
                                                16
                                        );


                                result.append(
                                        (char) codePoint
                                );


                                i += 4;


                            } catch (NumberFormatException e) {

                                result.append(
                                        "\\u"
                                );

                                result.append(
                                        hex
                                );


                                i += 4;
                            }

                        } else {

                            result.append(
                                    'u'
                            );
                        }
                    }


                    default ->
                            result.append(
                                    c
                            );
                }


                escaped =
                        false;


            } else if (c == '\\') {

                escaped =
                        true;


            } else if (c == '"') {

                break;


            } else {

                result.append(
                        c
                );
            }
        }


        return result
                .toString()
                .trim();
    }


    // ============================================================
    // JSON ESCAPING
    // ============================================================

    private String escapeJson(
            String value) {

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
                    value.charAt(
                            i
                    );


            switch (c) {

                case '\\' ->
                        escaped.append(
                                "\\\\"
                        );

                case '"' ->
                        escaped.append(
                                "\\\""
                        );

                case '\b' ->
                        escaped.append(
                                "\\b"
                        );

                case '\f' ->
                        escaped.append(
                                "\\f"
                        );

                case '\n' ->
                        escaped.append(
                                "\\n"
                        );

                case '\r' ->
                        escaped.append(
                                "\\r"
                        );

                case '\t' ->
                        escaped.append(
                                "\\t"
                        );

                default -> {

                    /*
                     * JSON strings cannot contain raw control
                     * characters U+0000 through U+001F.
                     */
                    if (c < 0x20) {

                        escaped.append(
                                String.format(
                                        "\\u%04x",
                                        (int) c
                                )
                        );

                    } else {

                        escaped.append(
                                c
                        );
                    }
                }
            }
        }


        return escaped.toString();
    }
}