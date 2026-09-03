package com.smartmail.backend.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.stereotype.Service;

import com.smartmail.backend.model.Email;
import com.smartmail.backend.repository.EmailRepository;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;

@Service
public class GmailService {

    private final EmailRepository emailRepository;
    private final EmailClassifierService classifierService;

    public GmailService(
            EmailRepository emailRepository,
            EmailClassifierService classifierService) {

        this.emailRepository = emailRepository;
        this.classifierService = classifierService;
    }

    public String getInboxMessages(
            @RegisteredOAuth2AuthorizedClient("google")
            OAuth2AuthorizedClient authorizedClient) {

        try {

            // ============================================================
            // GET GOOGLE ACCESS TOKEN
            // ============================================================

            String accessToken =
                    authorizedClient.getAccessToken().getTokenValue();

            GoogleCredentials credentials =
                    GoogleCredentials.create(
                            new AccessToken(accessToken, null));

            // ============================================================
            // CREATE GMAIL CLIENT
            // ============================================================

            Gmail gmail = new Gmail.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("SmartMail")
                    .build();

            // ============================================================
            // GET GMAIL INBOX MESSAGES
            // ============================================================

            ListMessagesResponse response =
                    gmail.users()
                            .messages()
                            .list("me")
                            .setLabelIds(List.of("INBOX"))
                            .setMaxResults(10L)
                            .execute();

            List<Message> messages = response.getMessages();

            if (messages == null || messages.isEmpty()) {

                return "<h2>No Gmail messages found.</h2>";
            }

            StringBuilder result = new StringBuilder();

            // ============================================================
            // HTML PAGE START
            // ============================================================

            result.append("""
                    <!DOCTYPE html>
                    <html>
                    <head>

                        <meta charset="UTF-8">

                        <title>SmartMail - Gmail</title>

                        <style>

                            * {
                                box-sizing: border-box;
                            }

                            body {
                                margin: 0;
                                padding: 30px;
                                background: #f4f6f8;
                                font-family: Arial, Helvetica, sans-serif;
                            }

                            .page-title {
                                max-width: 1000px;
                                margin: 0 auto 10px auto;
                                font-size: 28px;
                                font-weight: bold;
                                color: #222;
                            }

                            .status {
                                max-width: 1000px;
                                margin: 0 auto 25px auto;
                                color: #666;
                                font-size: 14px;
                            }

                            .email-card {
                                max-width: 1000px;
                                margin: 0 auto 25px auto;
                                background: white;
                                border-radius: 12px;
                                box-shadow: 0 3px 12px rgba(0,0,0,0.10);
                                overflow: hidden;
                            }

                            .email-header {
                                padding: 20px 25px;
                                background: #f8f9fa;
                                border-bottom: 1px solid #ddd;
                            }

                            .sender {
                                font-size: 17px;
                                font-weight: bold;
                                color: #222;
                                margin-bottom: 8px;
                            }

                            .subject {
                                font-size: 18px;
                                font-weight: bold;
                                color: #333;
                                margin-bottom: 8px;
                            }

                            .message-id {
                                font-size: 12px;
                                color: #888;
                            }

                            .classification {
                                padding: 15px 25px;
                                background: #f1f5f9;
                                border-bottom: 1px solid #ddd;
                                font-size: 15px;
                                line-height: 1.8;
                            }

                            .category {
                                font-weight: bold;
                            }

                            .confidence {
                                color: #555;
                            }

                            .action {
                                font-weight: bold;
                            }

                            .email-body {
                                padding: 0;
                                background: white;
                            }

                            .plain-body {
                                padding: 25px;
                                white-space: pre-wrap;
                                font-family: Arial, Helvetica, sans-serif;
                                font-size: 15px;
                                line-height: 1.6;
                                color: #222;
                            }

                            .email-frame {
                                width: 100%;
                                min-height: 400px;
                                height: 600px;
                                border: none;
                                display: block;
                                overflow: hidden;
                            }

                        </style>

                        <script>

                            // =================================================
                            // RESIZE EMAIL IFRAME
                            // =================================================

                            function resizeEmailFrame(frame) {

                                try {

                                    const doc =
                                        frame.contentDocument ||
                                        frame.contentWindow.document;

                                    const height = Math.max(
                                        doc.body.scrollHeight,
                                        doc.documentElement.scrollHeight
                                    );

                                    frame.style.height = height + "px";

                                } catch (e) {

                                    console.log(
                                        "Could not resize email:",
                                        e
                                    );
                                }
                            }


                            // =================================================
                            // UPDATE CLASSIFICATION FROM DATABASE
                            // =================================================

                            async function updateClassificationResults() {

                                try {

                                    const response =
                                        await fetch("/emails/gmail/results");

                                    if (!response.ok) {

                                        console.log(
                                            "SmartMail: Could not fetch DB results."
                                        );

                                        return;
                                    }

                                    const emails =
                                        await response.json();

                                    let pendingCount = 0;

                                    emails.forEach(function(email) {

                                        const messageId =
                                            email.gmailMessageId;

                                        if (!messageId) {
                                            return;
                                        }

                                        const card =
                                            document.querySelector(
                                                '[data-message-id="' +
                                                CSS.escape(messageId) +
                                                '"]'
                                            );

                                        if (!card) {
                                            return;
                                        }

                                        const categoryElement =
                                            card.querySelector(
                                                ".category-value"
                                            );

                                        const confidenceElement =
                                            card.querySelector(
                                                ".confidence-value"
                                            );

                                        const actionElement =
                                            card.querySelector(
                                                ".action-value"
                                            );

                                        // =====================================
                                        // UPDATE CATEGORY
                                        // =====================================

                                        if (categoryElement) {

                                            categoryElement.textContent =
                                                email.category ||
                                                "PENDING_REVIEW";
                                        }

                                        // =====================================
                                        // UPDATE CONFIDENCE
                                        // =====================================

                                        if (confidenceElement) {

                                            const confidence =
                                                email.confidence;

                                            confidenceElement.textContent =
                                                confidence === null ||
                                                confidence === undefined
                                                    ? "N/A"
                                                    : confidence;
                                        }

                                        // =====================================
                                        // UPDATE ACTION
                                        // =====================================

                                        if (actionElement) {

                                            actionElement.textContent =
                                                email.action ||
                                                "PENDING_REVIEW";
                                        }

                                        // =====================================
                                        // CHECK AI REVIEW STATUS
                                        // =====================================

                                        if (email.aiReviewed !== true) {

                                            pendingCount++;
                                        }

                                    });

                                    // =================================================
                                    // UPDATE PAGE STATUS
                                    // =================================================

                                    const status =
                                        document.getElementById(
                                            "refresh-status"
                                        );

                                    if (pendingCount > 0) {

                                        if (status) {

                                            status.textContent =
                                                "SmartMail AI review in progress...";
                                        }

                                    } else {

                                        if (status) {

                                            status.textContent =
                                                "SmartMail AI review complete.";
                                        }

                                        // =====================================
                                        // STOP POLLING
                                        // =====================================

                                        if (window.smartMailPolling) {

                                            clearInterval(
                                                window.smartMailPolling
                                            );

                                            window.smartMailPolling = null;
                                        }
                                    }

                                } catch (error) {

                                    console.log(
                                        "SmartMail: Error updating results:",
                                        error
                                    );
                                }
                            }


                            // =================================================
                            // START AUTOMATIC DB POLLING
                            // =================================================

                            document.addEventListener(
                                "DOMContentLoaded",
                                function() {

                                    setTimeout(
                                        updateClassificationResults,
                                        1000
                                    );

                                    window.smartMailPolling =
                                        setInterval(
                                            updateClassificationResults,
                                            3000
                                        );
                                }
                            );

                        </script>

                    </head>

                    <body>

                    <div class="page-title">
                        Gmail Messages Found: """)
                    .append(messages.size())
                    .append("""
                    </div>

                    <div
                        id="refresh-status"
                        class="status">
                        Loading SmartMail results...
                    </div>
                    """);

            // ============================================================
            // PROCESS EACH EMAIL
            // ============================================================

            for (Message message : messages) {

                // ========================================================
                // GET FULL EMAIL
                // ========================================================

                Message fullMessage =
                        gmail.users()
                                .messages()
                                .get("me", message.getId())
                                .setFormat("full")
                                .execute();

                String sender = "";
                String subject = "";

                // ========================================================
                // GET EMAIL HEADERS
                // ========================================================

                if (fullMessage.getPayload() != null &&
                        fullMessage.getPayload().getHeaders() != null) {

                    for (var header :
                            fullMessage.getPayload().getHeaders()) {

                        if ("From".equalsIgnoreCase(header.getName())) {

                            sender = header.getValue();
                        }

                        if ("Subject".equalsIgnoreCase(header.getName())) {

                            subject = header.getValue();
                        }
                    }
                }

                // ========================================================
                // EXTRACT STRUCTURED EMAIL SIGNALS
                // ========================================================

                EmailSignals signals =
                        extractEmailSignals(fullMessage);

                System.out.println(
                        "SmartMail signals: " + signals
                );

                // ========================================================
                // EXTRACT EMAIL BODY
                // ========================================================

                String body =
                        extractBody(fullMessage.getPayload());

                // ========================================================
                // FIND MIME TYPE
                // ========================================================

                String mimeType =
                        findBodyMimeType(fullMessage.getPayload());

                // ========================================================
                // CHECK IF EMAIL ALREADY EXISTS
                // ========================================================

                var existingEmail =
                        emailRepository.findByGmailMessageId(
                                fullMessage.getId());

                Email email;

                boolean shouldRunClassification = true;

                if (existingEmail.isPresent()) {

                    // ====================================================
                    // EXISTING EMAIL
                    // ====================================================

                    email = existingEmail.get();

                    email.setSender(sender);
                    email.setSubject(subject);
                    email.setBody(body);

                    // ====================================================
                    // ALREADY REVIEWED
                    // ====================================================

                    if (email.isAiReviewed()) {

                        shouldRunClassification = false;

                        System.out.println(
                                "SmartMail: Reusing existing result for "
                                        + email.getGmailMessageId()
                        );

                    } else {

                        System.out.println(
                                "SmartMail: Email requires classification/review: "
                                        + email.getGmailMessageId()
                        );
                    }

                } else {

                    // ====================================================
                    // NEW EMAIL
                    // ====================================================

                    email = new Email();

                    email.setGmailMessageId(fullMessage.getId());
                    email.setSender(sender);
                    email.setSubject(subject);
                    email.setBody(body);

                    System.out.println(
                            "SmartMail: New Gmail email detected: "
                                    + fullMessage.getId()
                    );
                }

                // ========================================================
                // RUN RULE CLASSIFICATION ONLY WHEN NECESSARY
                // ========================================================

                if (shouldRunClassification) {

                    email = classifierService.classify(
                            email,
                            signals.hasListUnsubscribe,
                            signals.hasListUnsubscribePost,
                            signals.bulkMail,
                            signals.automatedSender,
                            signals.displayName,
                            signals.domain,
                            signals.baseDomain
                    );

                    emailRepository.save(email);

                } else {

                    // ====================================================
                    // EXISTING REVIEWED EMAIL
                    // ====================================================

                    emailRepository.save(email);
                }

                // ========================================================
                // START ASYNC AI REVIEW ONLY IF NECESSARY
                // ========================================================

                if (!email.isAiReviewed() &&
                        "PENDING_REVIEW".equalsIgnoreCase(
                                email.getAction())) {

                    System.out.println(
                            "SmartMail: Starting AI review for "
                                    + email.getGmailMessageId()
                    );

                    classifierService.classifyWithGeminiAsync(
                            email,
                            signals.hasListUnsubscribe,
                            signals.hasListUnsubscribePost,
                            signals.bulkMail,
                            signals.automatedSender,
                            signals.domain,
                            reviewedEmail -> trashMessage(
                                    gmail,
                                    fullMessage
                            )
                    );
                }

                // ========================================================
                // IMMEDIATE GMAIL ACTION
                // ========================================================
                //
                // Rule-based high-confidence:
                //
                // SPAM         -> TRASH
                // PROMOTIONAL  -> TRASH
                // IMPORTANT    -> KEEP
                //
                // Low confidence is handled asynchronously by AI.
                //
                // ========================================================

                if ("TRASH".equalsIgnoreCase(email.getAction())) {

                    trashMessage(
                            gmail,
                            fullMessage
                    );
                }

                // ========================================================
                // EMAIL CARD START
                // ========================================================

                result.append("""
                        <div
                            class="email-card"
                            data-message-id=\"""")
                        .append(escapeAttribute(email.getGmailMessageId()))
                        .append("""
                        ">

                            <div class="email-header">

                                <div class="sender">
                                    From: """)
                        .append(escapeHtml(email.getSender()))
                        .append("""
                                </div>

                                <div class="subject">
                                    Subject: """)
                        .append(escapeHtml(email.getSubject()))
                        .append("""
                                </div>

                                <div class="message-id">
                                    Message ID: """)
                        .append(escapeHtml(email.getGmailMessageId()))
                        .append("""
                                </div>

                            </div>
                        """);

                // ========================================================
                // DISPLAY CLASSIFICATION
                // ========================================================

                result.append("""
                        <div class="classification">

                            <div class="category">
                                Category:
                                <span class="category-value">""")
                        .append(escapeHtml(email.getCategory()))
                        .append("""
                                </span>
                            </div>

                            <div class="confidence">
                                Confidence:
                                <span class="confidence-value">""")
                        .append(
                                email.getConfidence() == null
                                        ? "N/A"
                                        : email.getConfidence()
                        )
                        .append("""
                                </span>
                            </div>

                            <div class="action">
                                Action:
                                <span class="action-value">""")
                        .append(escapeHtml(email.getAction()))
                        .append("""
                                </span>
                            </div>

                        </div>
                        """);

                // ========================================================
                // EMAIL BODY START
                // ========================================================

                result.append("""
                            <div class="email-body">
                        """);

                // ========================================================
                // DISPLAY HTML EMAIL
                // ========================================================

                if ("text/html".equalsIgnoreCase(mimeType)) {

                    result.append("<iframe class=\"email-frame\" ")
                            .append("sandbox=\"allow-same-origin\" ")
                            .append("onload=\"resizeEmailFrame(this)\" ")
                            .append("srcdoc=\"")
                            .append(escapeAttribute(body))
                            .append("\"></iframe>");

                } else {

                    // ====================================================
                    // DISPLAY PLAIN TEXT EMAIL
                    // ====================================================

                    result.append("<div class=\"plain-body\">")
                            .append(escapeHtml(body))
                            .append("</div>");
                }

                // ========================================================
                // EMAIL CARD END
                // ========================================================

                result.append("""
                            </div>

                        </div>
                        """);
            }

            // ============================================================
            // HTML PAGE END
            // ============================================================

            result.append("""
                    </body>
                    </html>
                    """);

            return result.toString();

        } catch (Exception e) {

            e.printStackTrace();

            return """
                    <html>
                    <body>

                        <h2>Error fetching Gmail messages</h2>

                        <p>
                    """ + escapeHtml(e.getMessage()) + """
                        </p>

                    </body>
                    </html>
                    """;
        }
    }


    // ============================================================
    // STRUCTURED EMAIL SIGNAL EXTRACTION
    // ============================================================

    private EmailSignals extractEmailSignals(Message message) {

        EmailSignals signals = new EmailSignals();

        if (message == null ||
                message.getPayload() == null) {

            return signals;
        }

        String fromValue = "";

        if (message.getPayload().getHeaders() != null) {

            for (var header :
                    message.getPayload().getHeaders()) {

                String name = header.getName();
                String value = header.getValue();

                if ("From".equalsIgnoreCase(name)) {

                    fromValue =
                            value == null ? "" : value;
                }

                if ("List-Unsubscribe".equalsIgnoreCase(name)) {

                    signals.hasListUnsubscribe = true;
                }

                if ("List-Unsubscribe-Post".equalsIgnoreCase(name)) {

                    signals.hasListUnsubscribePost = true;
                }
            }
        }

        // ============================================================
        // PARSE SENDER
        // ============================================================

        parseSender(
                fromValue,
                signals
        );

        // ============================================================
        // AUTOMATED SENDER SIGNAL
        // ============================================================

        String normalizedSender =
                normalizeSignalText(fromValue);

        signals.automatedSender =
                containsSignal(normalizedSender, "noreply") ||
                        containsSignal(normalizedSender, "no reply") ||
                        containsSignal(normalizedSender, "do not reply") ||
                        containsSignal(normalizedSender, "donotreply") ||
                        containsSignal(normalizedSender, "notification") ||
                        containsSignal(normalizedSender, "notifications") ||
                        containsSignal(normalizedSender, "alert") ||
                        containsSignal(normalizedSender, "alerts") ||
                        containsSignal(normalizedSender, "statement") ||
                        containsSignal(normalizedSender, "statements");

        // ============================================================
        // BULK / MARKETING SIGNAL
        // ============================================================

        signals.bulkMail =
                signals.hasListUnsubscribe ||
                        signals.hasListUnsubscribePost;

        return signals;
    }


    // ============================================================
    // PARSE SENDER
    // ============================================================

    private void parseSender(
            String fromValue,
            EmailSignals signals) {

        if (fromValue == null ||
                fromValue.isBlank()) {

            return;
        }

        Pattern angleAddressPattern =
                Pattern.compile(
                        "<([^<>@\\s]+@[^<>@\\s]+)>"
                );

        Matcher angleMatcher =
                angleAddressPattern.matcher(fromValue);

        String emailAddress;

        if (angleMatcher.find()) {

            emailAddress =
                    angleMatcher.group(1);

            String displayName =
                    fromValue
                            .substring(
                                    0,
                                    angleMatcher.start()
                            )
                            .trim();

            signals.displayName =
                    removeOuterQuotes(displayName);

        } else {

            Pattern plainAddressPattern =
                    Pattern.compile(
                            "\\b[^\\s<>@]+@[^\\s<>@]+\\b"
                    );

            Matcher plainMatcher =
                    plainAddressPattern.matcher(fromValue);

            if (!plainMatcher.find()) {

                return;
            }

            emailAddress =
                    plainMatcher.group();

            signals.displayName = "";
        }

        emailAddress =
                emailAddress
                        .toLowerCase(Locale.ROOT)
                        .trim();

        signals.emailAddress =
                emailAddress;

        int atIndex =
                emailAddress.lastIndexOf('@');

        if (atIndex < 0 ||
                atIndex == emailAddress.length() - 1) {

            return;
        }

        signals.domain =
                emailAddress.substring(atIndex + 1);

        signals.baseDomain =
                extractBaseDomain(signals.domain);
    }


    // ============================================================
    // REMOVE OUTER QUOTES
    // ============================================================

    private String removeOuterQuotes(String value) {

        String result =
                value.trim();

        if (result.length() >= 2) {

            char first =
                    result.charAt(0);

            char last =
                    result.charAt(
                            result.length() - 1
                    );

            if ((first == '"' &&
                    last == '"') ||
                    (first == '\'' &&
                            last == '\'')) {

                return result.substring(
                        1,
                        result.length() - 1
                ).trim();
            }
        }

        return result;
    }


    // ============================================================
    // EXTRACT BASE DOMAIN
    // ============================================================

    private String extractBaseDomain(String domain) {

        if (domain == null ||
                domain.isBlank()) {

            return "";
        }

        String normalizedDomain =
                domain
                        .toLowerCase(Locale.ROOT)
                        .trim();

        String[] parts =
                normalizedDomain.split("\\.");

        if (parts.length <= 2) {

            return normalizedDomain;
        }

        String last =
                parts[parts.length - 1];

        String secondLast =
                parts[parts.length - 2];

        if (last.length() == 2 &&
                ("co".equals(secondLast) ||
                        "com".equals(secondLast) ||
                        "net".equals(secondLast) ||
                        "org".equals(secondLast) ||
                        "gov".equals(secondLast))) {

            if (parts.length >= 3) {

                return parts[parts.length - 3]
                        + "."
                        + secondLast
                        + "."
                        + last;
            }
        }

        return secondLast +
                "." +
                last;
    }


    // ============================================================
    // NORMALIZE SIGNAL TEXT
    // ============================================================

    private String normalizeSignalText(String value) {

        if (value == null) {

            return "";
        }

        return value
                .toLowerCase(Locale.ROOT)
                .replaceAll(
                        "[^a-z0-9]+",
                        " "
                )
                .trim()
                .replaceAll(
                        "\\s+",
                        " "
                );
    }


    // ============================================================
    // SAFE SIGNAL MATCHING
    // ============================================================

    private boolean containsSignal(
            String normalizedText,
            String term) {

        String normalizedTerm =
                normalizeSignalText(term);

        if (normalizedText.isBlank() ||
                normalizedTerm.isBlank()) {

            return false;
        }

        String paddedText =
                " " +
                        normalizedText +
                        " ";

        String paddedTerm =
                " " +
                        normalizedTerm +
                        " ";

        return paddedText.contains(
                paddedTerm
        );
    }


    // ============================================================
    // EMAIL SIGNAL CONTAINER
    // ============================================================

    private static class EmailSignals {

        private String displayName = "";

        private String emailAddress = "";

        private String domain = "";

        private String baseDomain = "";

        private boolean hasListUnsubscribe;

        private boolean hasListUnsubscribePost;

        private boolean bulkMail;

        private boolean automatedSender;

        @Override
        public String toString() {

            return "EmailSignals{" +
                    "displayName='" +
                    displayName +
                    '\'' +
                    ", emailAddress='" +
                    emailAddress +
                    '\'' +
                    ", domain='" +
                    domain +
                    '\'' +
                    ", baseDomain='" +
                    baseDomain +
                    '\'' +
                    ", hasListUnsubscribe=" +
                    hasListUnsubscribe +
                    ", hasListUnsubscribePost=" +
                    hasListUnsubscribePost +
                    ", bulkMail=" +
                    bulkMail +
                    ", automatedSender=" +
                    automatedSender +
                    '}';
        }
    }


    // ============================================================
    // TRASH GMAIL MESSAGE
    // ============================================================
    //
    // This replaces the previous ARCHIVE operation.
    //
    // SPAM / PROMOTIONAL -> Gmail Trash
    //
    // Before trashing, we fetch the current Gmail message and check
    // whether it already has the TRASH label.
    //
    // This prevents repeated Gmail actions when the page is refreshed.
    //
    // ============================================================

    private void trashMessage(
            Gmail gmail,
            Message message) {

        if (message == null ||
                message.getId() == null ||
                message.getId().isBlank()) {

            System.out.println(
                    "SmartMail: Cannot trash message because " +
                            "message ID is missing."
            );

            return;
        }

        try {

            String messageId =
                    message.getId();

            // ========================================================
            // GET CURRENT GMAIL LABELS
            // ========================================================

            Message currentMessage =
                    gmail.users()
                            .messages()
                            .get(
                                    "me",
                                    messageId
                            )
                            .setFormat("minimal")
                            .execute();

            List<String> labelIds =
                    currentMessage.getLabelIds();

            // ========================================================
            // ALREADY IN TRASH
            // ========================================================

            if (labelIds != null &&
                    labelIds.contains("TRASH")) {

                System.out.println(
                        "SmartMail: Gmail message "
                                + messageId
                                + " is already in Trash. "
                                + "Skipping duplicate action."
                );

                return;
            }

            // ========================================================
            // MOVE TO TRASH
            // ========================================================

            ModifyMessageRequest modifyRequest =
                    new ModifyMessageRequest()
                            .setAddLabelIds(
                                    List.of("TRASH")
                            )
                            .setRemoveLabelIds(
                                    List.of("INBOX")
                            );

            gmail.users()
                    .messages()
                    .modify(
                            "me",
                            messageId,
                            modifyRequest
                    )
                    .execute();

            System.out.println(
                    "SmartMail: Moved Gmail message to Trash: "
                            + messageId
            );

        } catch (Exception e) {

            System.err.println(
                    "SmartMail: Failed to move Gmail message to Trash "
                            + message.getId()
            );

            e.printStackTrace();
        }
    }


    // ============================================================
    // EXTRACT EMAIL BODY
    // ============================================================

    private String extractBody(MessagePart part) {

        if (part == null) {

            return "";
        }

        if (part.getBody() != null &&
                part.getBody().getData() != null) {

            String mimeType =
                    part.getMimeType();

            if ("text/plain".equalsIgnoreCase(mimeType) ||
                    "text/html".equalsIgnoreCase(mimeType)) {

                try {

                    byte[] decodedBytes =
                            Base64.getUrlDecoder()
                                    .decode(
                                            part.getBody().getData()
                                    );

                    return new String(
                            decodedBytes,
                            StandardCharsets.UTF_8
                    );

                } catch (Exception e) {

                    return "";
                }
            }
        }

        if (part.getParts() != null) {

            for (MessagePart child :
                    part.getParts()) {

                if ("text/html".equalsIgnoreCase(
                        child.getMimeType())) {

                    String body =
                            extractBody(child);

                    if (!body.isEmpty()) {

                        return body;
                    }
                }
            }

            for (MessagePart child :
                    part.getParts()) {

                if ("text/plain".equalsIgnoreCase(
                        child.getMimeType())) {

                    String body =
                            extractBody(child);

                    if (!body.isEmpty()) {

                        return body;
                    }
                }
            }

            for (MessagePart child :
                    part.getParts()) {

                String body =
                        extractBody(child);

                if (!body.isEmpty()) {

                    return body;
                }
            }
        }

        return "";
    }


    // ============================================================
    // FIND MIME TYPE
    // ============================================================

    private String findBodyMimeType(
            MessagePart part) {

        if (part == null) {

            return "";
        }

        if (part.getBody() != null &&
                part.getBody().getData() != null) {

            if ("text/html".equalsIgnoreCase(
                    part.getMimeType())) {

                return "text/html";
            }

            if ("text/plain".equalsIgnoreCase(
                    part.getMimeType())) {

                return "text/plain";
            }
        }

        if (part.getParts() != null) {

            for (MessagePart child :
                    part.getParts()) {

                if ("text/html".equalsIgnoreCase(
                        child.getMimeType())) {

                    return "text/html";
                }
            }

            for (MessagePart child :
                    part.getParts()) {

                if ("text/plain".equalsIgnoreCase(
                        child.getMimeType())) {

                    return "text/plain";
                }
            }

            for (MessagePart child :
                    part.getParts()) {

                String type =
                        findBodyMimeType(child);

                if (!type.isEmpty()) {

                    return type;
                }
            }
        }

        return "";
    }


    // ============================================================
    // ESCAPE NORMAL HTML TEXT
    // ============================================================

    private String escapeHtml(String text) {

        if (text == null) {

            return "";
        }

        return text
                .replace(
                        "&",
                        "&amp;"
                )
                .replace(
                        "<",
                        "&lt;"
                )
                .replace(
                        ">",
                        "&gt;"
                )
                .replace(
                        "\"",
                        "&quot;"
                )
                .replace(
                        "'",
                        "&#39;"
                );
    }


    // ============================================================
    // ESCAPE IFRAME SRCDOC ATTRIBUTE
    // ============================================================

    private String escapeAttribute(String text) {

        if (text == null) {

            return "";
        }

        return text
                .replace(
                        "&",
                        "&amp;"
                )
                .replace(
                        "\"",
                        "&quot;"
                )
                .replace(
                        "<",
                        "&lt;"
                )
                .replace(
                        ">",
                        "&gt;"
                );
    }
}