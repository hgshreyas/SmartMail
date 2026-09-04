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
            "llama3.2:3b";

    // Keep the prompt reasonably small so Ollama can respond faster.
    private static final int MAX_BODY_LENGTH = 2000;

    /*
     * Dedicated executor for Ollama requests.
     *
     * IMPORTANT:
     *
     * Ollama calls are blocking HTTP operations.
     *
     * We therefore DO NOT use Java's common ForkJoinPool.
     *
     * Only 3 Ollama requests are allowed to run at the same time.
     * Remaining requests wait safely in the executor queue.
     */
    private final ExecutorService ollamaExecutor =
            Executors.newFixedThreadPool(
                    3,
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
                                        + "\"format\":\"json\","
                                        + "\"keep_alive\":\"10m\","
                                        + "\"options\":{"
                                        + "\"temperature\":0,"
                                        + "\"num_predict\":64"
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
                You are SmartMail, an email classification system.

                Your task is to classify an email into EXACTLY ONE of:

                IMPORTANT
                PROMOTIONAL
                SPAM

                ============================================================
                CORE PRINCIPLE
                ============================================================

                Classify the email based primarily on the ACTUAL PURPOSE
                and CONTENT of the message.

                Do NOT classify an email only because of the sender name,
                company name, domain, or the fact that it is automated.

                A company can send different types of emails.

                For example, an investment company may send:
                - a trade/order confirmation -> IMPORTANT
                - an account/security alert -> IMPORTANT
                - an investment statement -> IMPORTANT
                - a marketing campaign -> PROMOTIONAL
                - an investment offer -> PROMOTIONAL

                Therefore, always examine the subject and body carefully.

                ============================================================
                IMPORTANT
                ============================================================

                Use IMPORTANT when the email contains information that a
                user would reasonably want to know or act on personally.

                Examples:

                - Bank transactions
                - UPI/payment confirmations
                - Money received or money sent
                - Debit or credit alerts
                - Credit/debit card transactions
                - OTPs
                - Login alerts
                - Password/security alerts
                - Account security warnings
                - Account changes
                - Account verification
                - Important financial statements
                - Investment transactions
                - Stock order placement
                - Stock order execution
                - Buy/sell confirmations
                - Mutual fund transaction confirmations
                - Demat/account activity
                - Tax-related financial documents
                - Job interview invitations
                - Job offer messages
                - Internship selection/interview messages
                - Important academic deadlines
                - Important work-related communication
                - Messages requiring a personal response or action

                IMPORTANT does NOT mean "this sender is important."

                The email itself must contain important personal,
                transactional, security, employment, academic, or
                work-related information.

                ============================================================
                PROMOTIONAL
                ============================================================

                Use PROMOTIONAL when the primary purpose is marketing,
                advertising, selling, engagement, or encouraging the user
                to purchase or use something.

                Examples:

                - Discounts
                - Coupons
                - Sales
                - Product offers
                - Promotional campaigns
                - Cashback offers
                - Investment promotions
                - Trading promotions
                - Mutual fund promotions
                - Credit card offers
                - Loan offers
                - Insurance offers
                - Shopping promotions
                - Restaurant offers
                - Travel offers
                - Entertainment promotions
                - Marketing newsletters
                - Product announcements intended mainly for marketing
                - Surveys
                - Referral campaigns
                - "Invest now"
                - "Limited time offer"
                - "Special offer"
                - "Get started today"
                - "Don't miss this opportunity"

                A financial company sending a marketing offer is
                PROMOTIONAL even though the company itself is legitimate.

                ============================================================
                SPAM
                ============================================================

                Use SPAM when the email is clearly suspicious, fraudulent,
                malicious, or deceptive.

                Examples:

                - Fake lottery winnings
                - Fake prizes
                - Fraudulent money offers
                - Requests for money from suspicious sources
                - Phishing
                - Credential theft
                - Fake account warnings
                - Suspicious links
                - Impersonation scams
                - Clearly malicious messages
                - Unrealistic financial promises
                - "You won" messages from unknown sources
                - Requests for passwords, OTPs, or sensitive information
                  from suspicious senders

                IMPORTANT:

                Do not classify a legitimate automated email as SPAM simply
                because it is automated.

                Do not classify an unfamiliar sender as SPAM unless the
                message itself provides evidence of suspicious or malicious
                behavior.

                ============================================================
                FINANCIAL EMAIL RULE
                ============================================================

                Financial emails require special attention.

                If the message reports an ACTUAL event involving the user's
                account, money, investment, or transaction, classify it as
                IMPORTANT.

                Examples:

                "Your order has been executed."
                -> IMPORTANT

                "₹5,000 has been credited to your account."
                -> IMPORTANT

                "Your mutual fund transaction was completed."
                -> IMPORTANT

                "Your monthly account statement is available."
                -> IMPORTANT

                But if the message is trying to SELL, ADVERTISE, or
                PROMOTE a financial product or service, classify it as
                PROMOTIONAL.

                Examples:

                "Invest in these top stocks today."
                -> PROMOTIONAL

                "Zero brokerage offer."
                -> PROMOTIONAL

                "Start your SIP today."
                -> PROMOTIONAL

                "Special trading offer for you."
                -> PROMOTIONAL

                ============================================================
                JOB / ACADEMIC RULE
                ============================================================

                Actual opportunities or actions involving the user are
                IMPORTANT.

                Examples:

                "You have been shortlisted for an interview."
                -> IMPORTANT

                "Your interview is scheduled for Monday."
                -> IMPORTANT

                "Your application has been selected."
                -> IMPORTANT

                General career newsletters or job advertisements are
                PROMOTIONAL.

                Examples:

                "Top 10 jobs this week."
                -> PROMOTIONAL

                "Explore thousands of job openings."
                -> PROMOTIONAL

                ============================================================
                GMAIL SIGNALS
                ============================================================

                Gmail metadata can help identify bulk or marketing email,
                but metadata is only SUPPORTING evidence.

                List-Unsubscribe:
                %s

                List-Unsubscribe-Post:
                %s

                Bulk mail:
                %s

                Automated sender:
                %s

                These signals should NOT override clear evidence from the
                actual subject and body.

                For example:

                A bulk/automated email containing a real transaction
                confirmation can still be IMPORTANT.

                ============================================================
                DECISION ORDER
                ============================================================

                Follow this reasoning order:

                1. Is the message clearly fraudulent, phishing, malicious,
                   or deceptive?
                   -> SPAM

                2. Otherwise, is it reporting a personal transaction,
                   account event, security event, actual job/interview,
                   academic deadline, or important work communication?
                   -> IMPORTANT

                3. Otherwise, is the primary purpose marketing,
                   advertising, selling, discounts, offers, newsletters,
                   surveys, or engagement?
                   -> PROMOTIONAL

                4. If still uncertain, use the category best supported by
                   the actual email content and give a lower confidence.

                ============================================================
                OUTPUT RULES
                ============================================================

                1. Return ONLY valid JSON.
                2. Do not write anything outside the JSON.
                3. category MUST be IMPORTANT, PROMOTIONAL, or SPAM.
                4. confidence MUST be a number from 0.0 to 1.0.
                5. reason must be short and explain the main reason.
                6. Do not create any other category.
                7. Do not use sender/domain alone as the reason.
                8. Use the email subject and body as the primary evidence.

                ============================================================
                EMAIL
                ============================================================

                Sender:
                %s

                Domain:
                %s

                Subject:
                %s

                Body:
                %s

                ============================================================
                REQUIRED OUTPUT
                ============================================================

                {
                  "category": "IMPORTANT",
                  "confidence": 0.95,
                  "reason": "Brief reason based on the email content"
                }
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

        return value
                .replace(
                        "\\",
                        "\\\\"
                )
                .replace(
                        "\"",
                        "\\\""
                )
                .replace(
                        "\r",
                        "\\r"
                )
                .replace(
                        "\n",
                        "\\n"
                )
                .replace(
                        "\t",
                        "\\t"
                );
    }
}