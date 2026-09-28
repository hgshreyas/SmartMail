package com.smartmail.backend.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.stereotype.Service;

import com.smartmail.backend.model.Email;
import com.smartmail.backend.repository.EmailRepository;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;

@Service
public class GmailService {

    // Process the full Gmail inbox in controlled chunks.
    // Only 50 full Gmail messages are handled at a time so a mailbox with
    // thousands of messages is processed sequentially without flooding
    // Gmail or starting parallel local-AI work.
    private static final int PROCESSING_BATCH_SIZE = 50;

    // Keep Gmail API usage comfortably below the per-user quota.
    // The normal Gmail pass is rules-first and NEVER invokes Ollama directly.
    private static final long MESSAGE_COOLDOWN_MS = 250L;
    private static final long BATCH_COOLDOWN_MS = 1_000L;

    // Gmail quota windows are minute-based. If Gmail reports a rate limit,
    // wait slightly longer than one minute and retry the SAME operation.
    private static final long RATE_LIMIT_BACKOFF_MS = 65_000L;
    private static final int MAX_RATE_LIMIT_RETRIES = 3;

    private final AtomicInteger progressTotal = new AtomicInteger(0);
    private final AtomicInteger progressProcessed = new AtomicInteger(0);
    private final AtomicInteger progressFailed = new AtomicInteger(0);
    private volatile boolean processing = false;
    private volatile String progressStage = "IDLE";

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

        synchronized (this) {

            if (processing) {

                System.out.println(
                        "SmartMail: Gmail bulk processing is already running."
                );

                return "<h2>SmartMail Gmail processing is already running.</h2>";
            }

            processing = true;
        }

        try {

            progressTotal.set(0);
            progressProcessed.set(0);
            progressFailed.set(0);
            progressStage = "FETCHING_GMAIL";

            // ============================================================
            // GET GOOGLE ACCESS TOKEN
            // ============================================================

            GoogleCredentials credentials =
                    createGoogleCredentials(
                            authorizedClient
                    );

            // ============================================================
            // CREATE GMAIL CLIENT
            // ============================================================

            Gmail gmail =
                    createGmailClient(
                            credentials
                    );

            // ============================================================
            // GET GMAIL INBOX MESSAGES
            // ============================================================

            // Gmail returns messages in pages. Each page can contain
            // at most 50 messages here. Keep requesting pages until Gmail
            // does not provide another page token.
            List<Message> messages = new ArrayList<>();
            String nextPageToken = null;

            do {

                ListMessagesResponse response =
                        listInboxPageWithRetry(
                                gmail,
                                nextPageToken
                        );

                if (response.getMessages() != null) {
                    messages.addAll(response.getMessages());
                }

                nextPageToken = response.getNextPageToken();

                System.out.println(
                        "SmartMail: Gmail pagination fetched "
                                + messages.size()
                                + " message(s) so far."
                );

            } while (nextPageToken != null && !nextPageToken.isBlank());

            if (messages == null || messages.isEmpty()) {

                progressTotal.set(0);
                progressProcessed.set(0);
                processing = false;
                progressStage = "NO_MESSAGES";

                return "<h2>No Gmail messages found.</h2>";
            }

            /*
             * Process the ENTIRE inbox snapshot, but do it in safe chunks of
             * PROCESSING_BATCH_SIZE. This lets SmartMail sort mailboxes with
             * thousands of messages while keeping Gmail requests sequential.
             *
             * IMPORTANT:
             * This normal Gmail pass does NOT run Ollama. Any unresolved email
             * is saved as PENDING_REVIEW and can later be handled by the
             * controlled PendingReviewService.
             */
            int totalMessagesFound = messages.size();

            progressTotal.set(totalMessagesFound);
            progressProcessed.set(0);
            progressFailed.set(0);
            progressStage = "PROCESSING_EMAILS";

            int totalBatches =
                    (totalMessagesFound + PROCESSING_BATCH_SIZE - 1)
                            / PROCESSING_BATCH_SIZE;

            System.out.println(
                    "SmartMail: Bulk processing "
                            + totalMessagesFound
                            + " Gmail message(s) in "
                            + totalBatches
                            + " sequential batch(es) of up to "
                            + PROCESSING_BATCH_SIZE
                            + "."
            );

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

                                        if (categoryElement) {

                                            categoryElement.textContent =
                                                email.category ||
                                                "PENDING_REVIEW";
                                        }

                                        if (confidenceElement) {

                                            const confidence =
                                                email.confidence;

                                            confidenceElement.textContent =
                                                confidence === null ||
                                                confidence === undefined
                                                    ? "N/A"
                                                    : confidence;
                                        }

                                        if (actionElement) {

                                            actionElement.textContent =
                                                email.action ||
                                                "PENDING_REVIEW";
                                        }

                                        if (email.aiReviewed !== true) {

                                            pendingCount++;
                                        }

                                    });

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
                    .append(totalMessagesFound)
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

            for (int batchStart = 0;
                 batchStart < messages.size();
                 batchStart += PROCESSING_BATCH_SIZE) {

                int batchEnd =
                        Math.min(
                                batchStart + PROCESSING_BATCH_SIZE,
                                messages.size()
                        );

                int batchNumber =
                        (batchStart / PROCESSING_BATCH_SIZE) + 1;

                List<Message> currentBatch =
                        messages.subList(
                                batchStart,
                                batchEnd
                        );

                progressStage =
                        "PROCESSING_GMAIL_BATCH_"
                                + batchNumber
                                + "_OF_"
                                + totalBatches;

                System.out.println(
                        "SmartMail: Starting Gmail batch "
                                + batchNumber
                                + "/"
                                + totalBatches
                                + " ("
                                + currentBatch.size()
                                + " message(s))."
                );

                for (Message message : currentBatch) {

                    try {

                        /*
                         * RESUME SUPPORT:
                         *
                         * A restarted bulk run should not download thousands of
                         * Gmail bodies that SmartMail already completed.
                         */
                        var cachedEmail =
                                emailRepository.findByGmailMessageId(
                                        message.getId()
                                );

                        if (cachedEmail.isPresent() &&
                                cachedEmail.get().isAiReviewed()) {

                            Email completedEmail =
                                    cachedEmail.get();

                            if ("TRASH".equalsIgnoreCase(
                                    completedEmail.getAction()
                            )) {

                                trashMessageById(
                                        gmail,
                                        message.getId()
                                );

                            } else {

                                System.out.println(
                                        "SmartMail: Skipping already-reviewed Gmail message "
                                                + message.getId()
                                );
                            }

                            int completed =
                                    progressProcessed.incrementAndGet();

                            System.out.println(
                                    "SmartMail: Processing progress "
                                            + completed
                                            + "/"
                                            + progressTotal.get()
                                            + " (failed="
                                            + progressFailed.get()
                                            + ")"
                            );

                            continue;
                        }

                        Message fullMessage =
                                getFullMessageWithRetry(
                                        gmail,
                                        message.getId()
                                );

                        String sender = "";
                        String subject = "";

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

                        EmailSignals signals =
                                extractEmailSignals(fullMessage);

                        System.out.println(
                                "SmartMail signals: " + signals
                        );

                        String body =
                                extractBody(fullMessage.getPayload());

                        String mimeType =
                                findBodyMimeType(fullMessage.getPayload());

                        var existingEmail =
                                cachedEmail;

                        Email email;

                        boolean shouldRunClassification = true;

                        if (existingEmail.isPresent()) {

                            email = existingEmail.get();

                            email.setSender(sender);
                            email.setSubject(subject);
                            email.setBody(body);

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

                            emailRepository.save(email);
                        }

                        if (!email.isAiReviewed() &&
                                "PENDING_REVIEW".equalsIgnoreCase(
                                        email.getAction())) {

                            /*
                             * Do not start Ollama directly from the normal Gmail fetch.
                             *
                             * Large mailboxes can contain many uncertain emails. Starting
                             * local AI here would keep the GPU under continuous load and
                             * can also cause semaphore rejections when multiple emails are
                             * discovered quickly.
                             *
                             * Leave uncertain emails as PENDING_REVIEW. They are processed
                             * separately by PendingReviewService, which uses the controlled
                             * small-batch/cooling workflow.
                             */
                            System.out.println(
                                    "SmartMail: Queued for controlled AI review: "
                                            + email.getGmailMessageId()
                            );
                        }

                        if ("TRASH".equalsIgnoreCase(email.getAction())) {

                            trashMessage(
                                    gmail,
                                    fullMessage
                            );
                        }

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

                        result.append("""
                            <div class="email-body">
                        """);

                        if ("text/html".equalsIgnoreCase(mimeType)) {

                            result.append("<iframe class=\"email-frame\" ")
                                    .append("sandbox=\"allow-same-origin\" ")
                                    .append("onload=\"resizeEmailFrame(this)\" ")
                                    .append("srcdoc=\"")
                                    .append(escapeAttribute(body))
                                    .append("\"></iframe>");

                        } else {

                            result.append("<div class=\"plain-body\">")
                                    .append(escapeHtml(body))
                                    .append("</div>");
                        }

                        result.append("""
                            </div>

                        </div>
                        """);

                        int completed = progressProcessed.incrementAndGet();

                        System.out.println(
                                "SmartMail: Processing progress "
                                        + completed
                                        + "/"
                                        + progressTotal.get()
                                        + " (failed="
                                        + progressFailed.get()
                                        + ")"
                        );

                    } catch (Exception messageError) {

                        int failed =
                                progressFailed.incrementAndGet();

                        int completed =
                                progressProcessed.incrementAndGet();

                        System.err.println(
                                "SmartMail: Could not process Gmail message "
                                        + message.getId()
                                        + ": "
                                        + messageError.getMessage()
                        );

                        System.out.println(
                                "SmartMail: Processing progress "
                                        + completed
                                        + "/"
                                        + progressTotal.get()
                                        + " (failed="
                                        + failed
                                        + ")"
                        );

                    } finally {

                        sleepSafely(
                                MESSAGE_COOLDOWN_MS
                        );
                    }
                }

                System.out.println(
                        "SmartMail: Completed Gmail batch "
                                + batchNumber
                                + "/"
                                + totalBatches
                                + "."
                );

                if (batchEnd < messages.size()) {

                    progressStage =
                            "COOLING_BETWEEN_GMAIL_BATCHES";

                    sleepSafely(
                            BATCH_COOLDOWN_MS
                    );
                }
            }

            result.append("""
                    </body>
                    </html>
                    """);

            progressStage = "GMAIL_BULK_COMPLETE";
            processing = false;

            return result.toString();

        } catch (Exception e) {
            processing = false;
            progressStage = "ERROR";

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
    // PROCESSING PROGRESS
    // ============================================================

    public Map<String, Object> getProcessingProgress() {

        return Map.of(
                "processing", processing,
                "stage", progressStage,
                "processed", progressProcessed.get(),
                "failed", progressFailed.get(),
                "total", progressTotal.get()
        );
    }


    // ============================================================
    // HUMAN REVIEW - KEEP EMAIL
    // ============================================================

    public Email keepEmail(
            OAuth2AuthorizedClient authorizedClient,
            Long emailId) {

        try {

            Email email =
                    emailRepository.findById(emailId)
                            .orElseThrow(
                                    () -> new IllegalArgumentException(
                                            "Email not found: " + emailId
                                    )
                            );

            String accessToken =
                    authorizedClient.getAccessToken().getTokenValue();

            GoogleCredentials credentials =
                    GoogleCredentials.create(
                            new AccessToken(accessToken, null));

            Gmail gmail = new Gmail.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("SmartMail")
                    .build();

            String messageId =
                    email.getGmailMessageId();

            Message currentMessage =
                    gmail.users()
                            .messages()
                            .get("me", messageId)
                            .setFormat("minimal")
                            .execute();

            List<String> labelIds =
                    currentMessage.getLabelIds();

            // ========================================================
            // IF MESSAGE IS IN TRASH, RESTORE IT TO INBOX
            // ========================================================

            if (labelIds != null &&
                    labelIds.contains("TRASH")) {

                ModifyMessageRequest restoreRequest =
                        new ModifyMessageRequest()
                                .setAddLabelIds(
                                        List.of("INBOX")
                                )
                                .setRemoveLabelIds(
                                        List.of("TRASH")
                                );

                gmail.users()
                        .messages()
                        .modify(
                                "me",
                                messageId,
                                restoreRequest
                        )
                        .execute();

                System.out.println(
                        "SmartMail: Restored Gmail message to Inbox: "
                                + messageId
                );
            }

            email.setAction("KEEP");
            email.setProcessed(true);
            email.setAiReviewed(true);

            return emailRepository.save(email);

        } catch (Exception e) {

            System.err.println(
                    "SmartMail: Failed to keep email: "
                            + emailId
            );

            e.printStackTrace();

            throw new RuntimeException(
                    "Could not keep email.",
                    e
            );
        }
    }


    // ============================================================
    // HUMAN REVIEW - MOVE EMAIL TO TRASH
    // ============================================================

    public Email trashEmail(
            OAuth2AuthorizedClient authorizedClient,
            Long emailId) {

        try {

            Email email =
                    emailRepository.findById(emailId)
                            .orElseThrow(
                                    () -> new IllegalArgumentException(
                                            "Email not found: " + emailId
                                    )
                            );

            String accessToken =
                    authorizedClient.getAccessToken().getTokenValue();

            GoogleCredentials credentials =
                    GoogleCredentials.create(
                            new AccessToken(accessToken, null));

            Gmail gmail = new Gmail.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("SmartMail")
                    .build();

            Message currentMessage =
                    gmail.users()
                            .messages()
                            .get(
                                    "me",
                                    email.getGmailMessageId()
                            )
                            .setFormat("minimal")
                            .execute();

            trashMessage(
                    gmail,
                    currentMessage
            );

            email.setAction("TRASH");
            email.setProcessed(true);
            email.setAiReviewed(true);

            return emailRepository.save(email);

        } catch (Exception e) {

            System.err.println(
                    "SmartMail: Failed to trash email: "
                            + emailId
            );

            e.printStackTrace();

            throw new RuntimeException(
                    "Could not move email to Trash.",
                    e
            );
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

        parseSender(
                fromValue,
                signals
        );

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

        List<String> labelIds =
                message.getLabelIds();

        if (labelIds != null &&
                labelIds.contains("TRASH")) {

            System.out.println(
                    "SmartMail: Gmail message "
                            + message.getId()
                            + " is already in Trash. "
                            + "Skipping duplicate action."
            );

            return;
        }

        trashMessageById(
                gmail,
                message.getId()
        );
    }


    // ============================================================
    // TRASH GMAIL MESSAGE BY ID
    // ============================================================

    private void trashMessageById(
            Gmail gmail,
            String messageId) {

        if (messageId == null ||
                messageId.isBlank()) {

            return;
        }

        ModifyMessageRequest modifyRequest =
                new ModifyMessageRequest()
                        .setAddLabelIds(
                                List.of("TRASH")
                        )
                        .setRemoveLabelIds(
                                List.of("INBOX")
                        );

        int retryCount = 0;

        while (true) {

            try {

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

                return;

            } catch (Exception e) {

                if (!isRateLimitException(e) ||
                        retryCount >= MAX_RATE_LIMIT_RETRIES) {

                    throw new RuntimeException(
                            "Could not move Gmail message to Trash: "
                                    + messageId,
                            e
                    );
                }

                retryCount++;

                waitForRateLimitReset(
                        "trash Gmail message " + messageId,
                        retryCount
                );
            }
        }
    }


    // ============================================================
    // CREATE REFRESHABLE GOOGLE CREDENTIALS
    // ============================================================

    private GoogleCredentials createGoogleCredentials(
            OAuth2AuthorizedClient authorizedClient) {

        String accessTokenValue =
                authorizedClient
                        .getAccessToken()
                        .getTokenValue();

        Date expirationTime =
                authorizedClient
                        .getAccessToken()
                        .getExpiresAt() == null
                        ? null
                        : Date.from(
                        authorizedClient
                                .getAccessToken()
                                .getExpiresAt()
                );

        AccessToken accessToken =
                new AccessToken(
                        accessTokenValue,
                        expirationTime
                );

        /*
         * The old implementation used GoogleCredentials.create(accessToken).
         * That credential cannot refresh itself, which is why a long 2k+
         * mailbox run started failing when the access token expired.
         *
         * If Spring received a Google refresh token, build UserCredentials
         * so HttpCredentialsAdapter can refresh access tokens automatically.
         */
        if (authorizedClient.getRefreshToken() != null &&
                authorizedClient
                        .getRefreshToken()
                        .getTokenValue() != null &&
                !authorizedClient
                        .getRefreshToken()
                        .getTokenValue()
                        .isBlank()) {

            var registration =
                    authorizedClient
                            .getClientRegistration();

            UserCredentials.Builder builder =
                    UserCredentials
                            .newBuilder()
                            .setClientId(
                                    registration.getClientId()
                            )
                            .setClientSecret(
                                    registration.getClientSecret()
                            )
                            .setAccessToken(
                                    accessToken
                            )
                            .setRefreshToken(
                                    authorizedClient
                                            .getRefreshToken()
                                            .getTokenValue()
                            );

            String tokenUri =
                    registration
                            .getProviderDetails()
                            .getTokenUri();

            if (tokenUri != null &&
                    !tokenUri.isBlank()) {

                builder.setTokenServerUri(
                        URI.create(
                                tokenUri
                        )
                );
            }

            System.out.println(
                    "SmartMail: Gmail OAuth credentials support automatic access-token refresh."
            );

            return builder.build();
        }

        /*
         * Fallback for accounts where Google did not issue a refresh token.
         * Resume logic still prevents completed messages from being redone.
         */
        System.out.println(
                "SmartMail: No Google refresh token is available. "
                        + "Using the current access token for this run."
        );

        return GoogleCredentials.create(
                accessToken
        );
    }


    // ============================================================
    // CREATE GMAIL CLIENT
    // ============================================================

    private Gmail createGmailClient(
            GoogleCredentials credentials)
            throws Exception {

        return new Gmail.Builder(
                GoogleNetHttpTransport
                        .newTrustedTransport(),
                GsonFactory
                        .getDefaultInstance(),
                new HttpCredentialsAdapter(
                        credentials
                ))
                .setApplicationName(
                        "SmartMail"
                )
                .build();
    }


    // ============================================================
    // GMAIL LIST PAGE WITH RATE-LIMIT RETRY
    // ============================================================

    private ListMessagesResponse listInboxPageWithRetry(
            Gmail gmail,
            String pageToken)
            throws Exception {

        int retryCount = 0;

        while (true) {

            try {

                return gmail.users()
                        .messages()
                        .list("me")
                        .setLabelIds(
                                List.of("INBOX")
                        )
                        .setMaxResults(50L)
                        .setPageToken(pageToken)
                        .execute();

            } catch (Exception e) {

                if (!isRateLimitException(e) ||
                        retryCount >= MAX_RATE_LIMIT_RETRIES) {

                    throw e;
                }

                retryCount++;

                waitForRateLimitReset(
                        "list Gmail inbox page",
                        retryCount
                );
            }
        }
    }


    // ============================================================
    // FULL GMAIL MESSAGE WITH RATE-LIMIT RETRY
    // ============================================================

    private Message getFullMessageWithRetry(
            Gmail gmail,
            String messageId)
            throws Exception {

        int retryCount = 0;

        while (true) {

            try {

                return gmail.users()
                        .messages()
                        .get(
                                "me",
                                messageId
                        )
                        .setFormat("full")
                        .execute();

            } catch (Exception e) {

                if (!isRateLimitException(e) ||
                        retryCount >= MAX_RATE_LIMIT_RETRIES) {

                    throw e;
                }

                retryCount++;

                waitForRateLimitReset(
                        "read Gmail message " + messageId,
                        retryCount
                );
            }
        }
    }


    // ============================================================
    // RATE LIMIT DETECTION
    // ============================================================

    private boolean isRateLimitException(
            Throwable throwable) {

        Throwable current =
                throwable;

        while (current != null) {

            if (current instanceof GoogleJsonResponseException responseException) {

                int statusCode =
                        responseException.getStatusCode();

                String content =
                        responseException.getContent();

                String normalizedContent =
                        content == null
                                ? ""
                                : content.toLowerCase(
                                Locale.ROOT
                        );

                if (statusCode == 429) {

                    return true;
                }

                if (statusCode == 403 &&
                        (normalizedContent.contains(
                                "ratelimitexceeded"
                        ) ||
                                normalizedContent.contains(
                                        "rate_limit_exceeded"
                                ) ||
                                normalizedContent.contains(
                                        "quota exceeded"
                                ))) {

                    return true;
                }
            }

            String message =
                    current.getMessage();

            if (message != null) {

                String normalizedMessage =
                        message.toLowerCase(
                                Locale.ROOT
                        );

                if (normalizedMessage.contains(
                        "rate_limit_exceeded"
                ) ||
                        normalizedMessage.contains(
                                "ratelimitexceeded"
                        ) ||
                        normalizedMessage.contains(
                                "quota exceeded"
                        ) ||
                        normalizedMessage.contains(
                                "too many requests"
                        )) {

                    return true;
                }
            }

            current =
                    current.getCause();
        }

        return false;
    }


    // ============================================================
    // RATE LIMIT BACKOFF
    // ============================================================

    private void waitForRateLimitReset(
            String operation,
            int retryNumber) {

        String previousStage =
                progressStage;

        progressStage =
                "GMAIL_RATE_LIMIT_BACKOFF";

        System.out.println(
                "SmartMail: Gmail rate limit reached while trying to "
                        + operation
                        + ". Waiting "
                        + (RATE_LIMIT_BACKOFF_MS / 1000)
                        + " seconds before retry "
                        + retryNumber
                        + "/"
                        + MAX_RATE_LIMIT_RETRIES
                        + "."
        );

        sleepSafely(
                RATE_LIMIT_BACKOFF_MS
        );

        progressStage =
                previousStage;
    }


    // ============================================================
    // SAFE PAUSE
    // ============================================================

    private void sleepSafely(
            long milliseconds) {

        try {

            Thread.sleep(
                    milliseconds
            );

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            throw new IllegalStateException(
                    "Gmail processing was interrupted.",
                    e
            );
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