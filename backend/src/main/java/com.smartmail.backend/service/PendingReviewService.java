package com.smartmail.backend.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;

import com.smartmail.backend.model.Email;
import com.smartmail.backend.repository.EmailRepository;
import com.smartmail.backend.model.AppUser;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PendingReviewService {

    /*
     * Recheck the entire real pending backlog with deterministic rules
     * before spending local-AI time. Rules themselves do not use Ollama.
     */

    /*
     * Local AI remains strictly sequential: one email at a time.
     * There is no per-run cap, so one pending-review run can drain
     * the entire remaining uncertain queue automatically.
     */

    /*
     * Gmail API pacing/backoff for a large pending backlog.
     */
    private static final long GMAIL_MESSAGE_COOLDOWN_MS = 350L;
    private static final long RATE_LIMIT_BACKOFF_MS = 65_000L;
    private static final int MAX_RATE_LIMIT_RETRIES = 3;

    /*
     * Wait at most 90 seconds for one lightweight AI review before
     * leaving that email PENDING_REVIEW for a future run.
     */
    private static final long MAX_BATCH_WAIT_MS = 90_000L;

    private static final long STATUS_CHECK_DELAY_MS = 1_000L;

    private final EmailRepository emailRepository;
    private final EmailClassifierService classifierService;

    private final AtomicBoolean processing =
            new AtomicBoolean(false);

    /*
     * Number of real emails selected for THIS run.
     */
    private final AtomicInteger total =
            new AtomicInteger(0);

    /*
     * Full pending backlog size when the current run started.
     */
    private final AtomicInteger backlogAtStart =
            new AtomicInteger(0);

    private final AtomicInteger submitted =
            new AtomicInteger(0);

    private final AtomicInteger completed =
            new AtomicInteger(0);

    private final AtomicInteger failed =
            new AtomicInteger(0);

    private volatile String stage = "IDLE";
    private volatile Long progressOwnerId;


    public PendingReviewService(
            EmailRepository emailRepository,
            EmailClassifierService classifierService) {

        this.emailRepository = emailRepository;
        this.classifierService = classifierService;
    }


    // ============================================================
    // START BACKGROUND PROCESSING
    // ============================================================

    public boolean startProcessing(
            OAuth2AuthorizedClient authorizedClient,
            AppUser owner) {

        if (authorizedClient == null) {

            throw new IllegalArgumentException(
                    "Google OAuth client is required."
            );
        }

        /*
         * Prevent multiple pending-review workers from running
         * at the same time.
         */
        if (!processing.compareAndSet(false, true)) {

            return false;
        }

        total.set(0);
        backlogAtStart.set(0);
        submitted.set(0);
        completed.set(0);
        failed.set(0);

        stage = "STARTING";
        progressOwnerId = owner.getId();

        CompletableFuture.runAsync(() -> {

            try {

                processPendingEmails(
                        authorizedClient,
                        owner
                );

            } catch (Exception e) {

                stage = "ERROR";

                System.err.println(
                        "SmartMail: Pending-review processor failed: "
                                + e.getMessage()
                );

                e.printStackTrace();

            } finally {

                processing.set(false);

                if (!"ERROR".equals(stage)) {

                    stage = "COMPLETE";
                }
            }
        });

        return true;
    }


    // ============================================================
    // PROCESS PENDING EMAILS
    // ============================================================

    private void processPendingEmails(
            OAuth2AuthorizedClient authorizedClient,
            AppUser owner)
            throws Exception {

        stage = "LOADING_PENDING_EMAILS";

        List<Email> pendingEmails =
                new ArrayList<>();

        for (Email candidate :
                emailRepository.findByOwnerOrderByIdAsc(owner)) {

            if (candidate != null &&
                    "PENDING_REVIEW".equalsIgnoreCase(
                            candidate.getAction()
                    )) {

                pendingEmails.add(
                        candidate
                );
            }
        }

        pendingEmails.sort(
                (left, right) -> {

                    Long leftId =
                            left == null
                                    ? null
                                    : left.getId();

                    Long rightId =
                            right == null
                                    ? null
                                    : right.getId();

                    if (leftId == null &&
                            rightId == null) {

                        return 0;
                    }

                    if (leftId == null) {

                        return 1;
                    }

                    if (rightId == null) {

                        return -1;
                    }

                    return Long.compare(
                            leftId,
                            rightId
                    );
                }
        );

        backlogAtStart.set(
                pendingEmails.size()
        );

        System.out.println(
                "SmartMail: Pending-review backlog contains "
                        + pendingEmails.size()
                        + " email(s)."
        );

        if (pendingEmails.isEmpty()) {

            total.set(0);

            stage = "COMPLETE";

            return;
        }


        // ========================================================
        // SELECT REAL GMAIL EMAILS
        // ========================================================

        List<Email> runEmails =
                new ArrayList<>();

        for (Email email :
                pendingEmails) {

            if (email == null) {

                continue;
            }

            String gmailMessageId =
                    email.getGmailMessageId();

            /*
             * Ignore artificial retry test records.
             */
            if (gmailMessageId != null &&
                    gmailMessageId.startsWith(
                            "retry-test-"
                    )) {

                continue;
            }

            runEmails.add(
                    email
            );
        }

        total.set(
                runEmails.size()
        );

        System.out.println(
                "SmartMail: Rule-first backlog pass will check "
                        + runEmails.size()
                        + " email(s) without Ollama."
        );

        if (runEmails.isEmpty()) {

            System.out.println(
                    "SmartMail: No real Gmail emails are available "
                            + "for pending-review processing."
            );

            stage = "COMPLETE";

            return;
        }


        // ========================================================
        // CREATE GMAIL CLIENT
        // ========================================================

        Gmail gmail =
                createGmailClient(
                        authorizedClient
                );


        // ========================================================
        // RULE-FIRST REVIEW
        // ========================================================

        stage = "RULE_FIRST_REVIEW";

        List<Long> aiCandidateIds =
                new ArrayList<>();

        List<Message> aiCandidateMessages =
                new ArrayList<>();

        List<EmailSignals> aiCandidateSignals =
                new ArrayList<>();

        int rulesResolved = 0;

        int stillPendingAfterRules = 0;


        for (Email originalEmail :
                runEmails) {

            if (originalEmail == null ||
                    originalEmail.getId() == null) {

                failed.incrementAndGet();

                continue;
            }


            Email email =
                    emailRepository
                            .findByIdAndOwner(originalEmail.getId(), owner)
                            .orElse(null);

            if (email == null) {

                failed.incrementAndGet();

                continue;
            }


            /*
             * The email could have been manually reviewed while
             * this background process was running.
             */
            if (!"PENDING_REVIEW".equalsIgnoreCase(
                    email.getAction()
            )) {

                completed.incrementAndGet();

                continue;
            }


            /*
             * AI-reviewed PENDING_REVIEW emails represent emails
             * waiting for human review.
             *
             * Give newer deterministic rules one final opportunity
             * to resolve them without sending them to Ollama again.
             */
            boolean wasAlreadyAiReviewed =
                    email.isAiReviewed();

            if (wasAlreadyAiReviewed) {

                email.setAiReviewed(
                        false
                );
            }


            String gmailMessageId =
                    email.getGmailMessageId();

            if (gmailMessageId == null ||
                    gmailMessageId.isBlank()) {

                System.err.println(
                        "SmartMail: Pending email "
                                + email.getId()
                                + " has no Gmail message ID."
                );

                failed.incrementAndGet();

                continue;
            }

            if (gmailMessageId.startsWith(
                    "retry-test-"
            )) {

                continue;
            }


            try {

                Message fullMessage =
                        getFullMessageWithRetry(
                                gmail,
                                gmailMessageId
                        );


                EmailSignals signals =
                        extractEmailSignals(
                                fullMessage
                        );


                /*
                 * Run the latest deterministic classifier first.
                 */
                Email ruleReviewedEmail =
                        classifierService.classify(
                                email,
                                signals.hasListUnsubscribe,
                                signals.hasListUnsubscribePost,
                                signals.bulkMail,
                                signals.automatedSender,
                                signals.displayName,
                                signals.domain,
                                signals.baseDomain
                        );


                /*
                 * If this was already AI reviewed previously and the
                 * newest rules still cannot decide, preserve its
                 * human-review state.
                 */
                if (wasAlreadyAiReviewed &&
                        "PENDING_REVIEW".equalsIgnoreCase(
                                ruleReviewedEmail.getAction()
                        )) {

                    ruleReviewedEmail.setAiReviewed(
                            true
                    );
                }


                emailRepository.save(
                        ruleReviewedEmail
                );


                /*
                 * Rule resolved the email as trash.
                 */
                if ("TRASH".equalsIgnoreCase(
                        ruleReviewedEmail.getAction()
                )) {

                    trashMessage(
                            gmail,
                            fullMessage
                    );
                }


                /*
                 * Rule resolved the email completely.
                 */
                if (!"PENDING_REVIEW".equalsIgnoreCase(
                        ruleReviewedEmail.getAction()
                )) {

                    rulesResolved++;

                    completed.incrementAndGet();

                    System.out.println(
                            "SmartMail: Rules resolved backlog email "
                                    + gmailMessageId
                                    + " -> "
                                    + ruleReviewedEmail.getCategory()
                                    + " / "
                                    + ruleReviewedEmail.getAction()
                    );

                    continue;
                }


                stillPendingAfterRules++;


                /*
                 * Only emails that have NEVER completed AI review
                 * are submitted to Ollama.
                 *
                 * There is deliberately NO per-run AI limit.
                 *
                 * They will still be processed sequentially,
                 * one email at a time.
                 */
                if (!ruleReviewedEmail.isAiReviewed()) {

                    aiCandidateIds.add(
                            ruleReviewedEmail.getId()
                    );

                    aiCandidateMessages.add(
                            fullMessage
                    );

                    aiCandidateSignals.add(
                            signals
                    );
                }


            } catch (Exception e) {

                failed.incrementAndGet();

                System.err.println(
                        "SmartMail: Could not rule-review pending email "
                                + gmailMessageId
                                + ": "
                                + e.getMessage()
                );

            } finally {

                /*
                 * Avoid flooding the Gmail API while walking through
                 * hundreds or thousands of historical messages.
                 */
                sleepSafely(
                        GMAIL_MESSAGE_COOLDOWN_MS
                );
            }
        }


        System.out.println(
                "SmartMail: Rule-first backlog pass finished. "
                        + "Resolved by rules="
                        + rulesResolved
                        + ", still pending="
                        + stillPendingAfterRules
        );


        // ========================================================
        // SEQUENTIAL AI REVIEW
        // ========================================================

        /*
         * Every unresolved candidate is allowed to enter this run.
         *
         * IMPORTANT:
         *
         * This does NOT mean all emails run simultaneously.
         *
         * Ollama receives:
         *
         * email 1
         * wait
         * email 2
         * wait
         * email 3
         * wait
         * ...
         *
         * Therefore Ollama remains strictly sequential.
         */
        stage = "PROCESSING_AI_EMAILS";


        int aiCount =
                aiCandidateIds.size();


        System.out.println(
                "SmartMail: Sequential AI phase will review "
                        + aiCount
                        + " email(s), one at a time."
        );


        for (int i = 0;
             i < aiCount;
             i++) {


            final Long selectedId =
                    aiCandidateIds.get(
                            i
                    );


            final Message selectedMessage =
                    aiCandidateMessages.get(
                            i
                    );


            final EmailSignals selectedSignals =
                    aiCandidateSignals.get(
                            i
                    );


            Email selectedEmail =
                    emailRepository
                            .findByIdAndOwner(selectedId, owner)
                            .orElse(null);


            if (selectedEmail == null ||
                    selectedEmail.isAiReviewed() ||
                    !"PENDING_REVIEW".equalsIgnoreCase(
                            selectedEmail.getAction()
                    )) {

                continue;
            }


            System.out.println(
                    "SmartMail: Starting sequential AI review "
                            + (i + 1)
                            + "/"
                            + aiCount
                            + " for "
                            + selectedEmail.getGmailMessageId()
            );


            classifierService
                    .classifyWithAiAsync(
                            selectedEmail,
                            selectedSignals.hasListUnsubscribe,
                            selectedSignals.hasListUnsubscribePost,
                            selectedSignals.bulkMail,
                            selectedSignals.automatedSender,
                            selectedSignals.domain,
                            reviewedEmail ->
                                    trashMessage(
                                            gmail,
                                            selectedMessage
                                    )
                    );


            submitted.incrementAndGet();


            /*
             * Wait for THIS email before submitting another one.
             */
            waitForBatch(
                    List.of(selectedId), owner
            );
        }


        // ========================================================
        // FINAL QUEUE STATUS
        // ========================================================

        List<Email> remaining =
                emailRepository
                        .findByOwnerAndActionAndAiReviewedFalseOrderByIdAsc(
                                owner, "PENDING_REVIEW"
                        );


        System.out.println(
                "SmartMail: Pending-review run finished. "
                        + "Remaining AI-unreviewed emails: "
                        + remaining.size()
        );


        int manualReviewRemaining = 0;


        for (Email candidate :
                emailRepository.findByOwnerOrderByIdAsc(owner)) {

            if (candidate == null ||
                    !"PENDING_REVIEW".equalsIgnoreCase(
                            candidate.getAction()
                    ) ||
                    !candidate.isAiReviewed()) {

                continue;
            }


            String candidateMessageId =
                    candidate.getGmailMessageId();


            if (candidateMessageId != null &&
                    candidateMessageId.startsWith(
                            "retry-test-"
                    )) {

                continue;
            }


            manualReviewRemaining++;
        }


        System.out.println(
                "SmartMail: Remaining manual-review emails: "
                        + manualReviewRemaining
        );


        System.out.println(
                "SmartMail: Sequential AI queue drain finished; "
                        + "Ollama remained one-at-a-time."
        );
    }


    // ============================================================
    // WAIT FOR CURRENT AI EMAIL
    // ============================================================

    private void waitForBatch(
            List<Long> batchIds,
            AppUser owner) {

        if (batchIds == null ||
                batchIds.isEmpty()) {

            return;
        }


        long deadline =
                System.currentTimeMillis()
                        + MAX_BATCH_WAIT_MS;


        while (System.currentTimeMillis()
                < deadline) {


            boolean allFinished =
                    true;


            for (Long emailId :
                    batchIds) {


                Email current =
                        emailRepository
                                .findByIdAndOwner(emailId, owner)
                                .orElse(null);


                if (current != null &&
                        !current.isAiReviewed() &&
                        "PENDING_REVIEW".equalsIgnoreCase(
                                current.getAction()
                        )) {


                    allFinished =
                            false;


                    break;
                }
            }


            if (allFinished) {


                markBatchCompleted(
                        batchIds, owner
                );


                return;
            }


            try {


                Thread.sleep(
                        STATUS_CHECK_DELAY_MS
                );


            } catch (InterruptedException e) {


                Thread.currentThread()
                        .interrupt();


                break;
            }
        }


        /*
         * Timeout does not remove or alter the email.
         *
         * The email remains PENDING_REVIEW so that it may
         * be retried later.
         */
        for (Long emailId :
                batchIds) {


            Email current =
                    emailRepository
                            .findByIdAndOwner(emailId, owner)
                            .orElse(null);


            if (current != null &&
                    (current.isAiReviewed() ||
                            !"PENDING_REVIEW"
                                    .equalsIgnoreCase(
                                            current.getAction()
                                    ))) {


                completed.incrementAndGet();


            } else {


                failed.incrementAndGet();


                System.err.println(
                        "SmartMail: Pending-review timeout for email ID "
                                + emailId
                );
            }
        }
    }


    // ============================================================
    // COUNT COMPLETED AI EMAIL
    // ============================================================

    private void markBatchCompleted(
            List<Long> batchIds,
            AppUser owner) {


        for (Long emailId :
                batchIds) {


            Email current =
                    emailRepository
                            .findByIdAndOwner(emailId, owner)
                            .orElse(null);


            if (current != null &&
                    (current.isAiReviewed() ||
                            !"PENDING_REVIEW"
                                    .equalsIgnoreCase(
                                            current.getAction()
                                    ))) {


                completed.incrementAndGet();


            } else {


                failed.incrementAndGet();
            }
        }
    }


    // ============================================================
    // PROGRESS
    // ============================================================

    public Map<String, Object> getProgress(AppUser owner) {
        if (progressOwnerId != null && !progressOwnerId.equals(owner.getId())) {
            return Map.of("processing", false, "stage", "IDLE", "backlogAtStart", 0,
                    "total", 0, "submitted", 0, "completed", 0, "failed", 0);
        }
        return Map.of(
                "processing",
                processing.get(),

                "stage",
                stage,

                "backlogAtStart",
                backlogAtStart.get(),

                "total",
                total.get(),

                "submitted",
                submitted.get(),

                "completed",
                completed.get(),

                "failed",
                failed.get()
        );
    }


    // ============================================================
    // CREATE GMAIL CLIENT
    // ============================================================

    private Gmail createGmailClient(
            OAuth2AuthorizedClient authorizedClient)
            throws Exception {


        GoogleCredentials credentials =
                createGoogleCredentials(
                        authorizedClient
                );


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
    // REFRESHABLE GOOGLE CREDENTIALS
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
         * When Google issued a refresh token, create refreshable
         * UserCredentials so long-running Gmail jobs can continue
         * after the short-lived access token expires.
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
                    "SmartMail: Pending-review Gmail client "
                            + "supports automatic access-token refresh."
            );


            return builder.build();
        }


        /*
         * Fallback. This should normally not happen now that
         * SecurityConfig requests Google's offline access.
         */
        System.out.println(
                "SmartMail: Pending-review Gmail client has no "
                        + "refresh token; using current access token."
        );


        return GoogleCredentials.create(
                accessToken
        );
    }


    // ============================================================
    // FULL GMAIL MESSAGE WITH RATE-LIMIT RETRY
    // ============================================================

    private Message getFullMessageWithRetry(
            Gmail gmail,
            String messageId)
            throws Exception {


        int retryCount =
                0;


        while (true) {


            try {


                return gmail.users()
                        .messages()
                        .get(
                                "me",
                                messageId
                        )
                        .setFormat(
                                "full"
                        )
                        .execute();


            } catch (Exception e) {


                if (!isRateLimitException(
                        e
                ) ||
                        retryCount >=
                                MAX_RATE_LIMIT_RETRIES) {


                    throw e;
                }


                retryCount++;


                waitForRateLimitReset(
                        "read pending Gmail message "
                                + messageId,
                        retryCount
                );
            }
        }
    }


    // ============================================================
    // GMAIL MODIFY WITH RATE-LIMIT RETRY
    // ============================================================

    private void modifyMessageWithRetry(
            Gmail gmail,
            String messageId,
            ModifyMessageRequest request)
            throws Exception {


        int retryCount =
                0;


        while (true) {


            try {


                gmail.users()
                        .messages()
                        .modify(
                                "me",
                                messageId,
                                request
                        )
                        .execute();


                return;


            } catch (Exception e) {


                if (!isRateLimitException(
                        e
                ) ||
                        retryCount >=
                                MAX_RATE_LIMIT_RETRIES) {


                    throw e;
                }


                retryCount++;


                waitForRateLimitReset(
                        "modify pending Gmail message "
                                + messageId,
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


            if (current instanceof
                    GoogleJsonResponseException responseException) {


                int statusCode =
                        responseException
                                .getStatusCode();


                String content =
                        responseException
                                .getContent();


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
                stage;


        stage =
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


        stage =
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
                    "Pending-review processing was interrupted.",
                    e
            );
        }
    }


    // ============================================================
    // EMAIL SIGNAL EXTRACTION
    // ============================================================

    private EmailSignals extractEmailSignals(
            Message message) {


        EmailSignals signals =
                new EmailSignals();


        if (message == null ||
                message.getPayload() == null) {


            return signals;
        }


        String fromValue =
                "";


        if (message.getPayload()
                .getHeaders() != null) {


            for (var header :
                    message.getPayload()
                            .getHeaders()) {


                String name =
                        header.getName();


                String value =
                        header.getValue();


                if ("From".equalsIgnoreCase(
                        name
                )) {


                    fromValue =
                            value == null
                                    ? ""
                                    : value;
                }


                if ("List-Unsubscribe"
                        .equalsIgnoreCase(
                                name
                        )) {


                    signals.hasListUnsubscribe =
                            true;
                }


                if ("List-Unsubscribe-Post"
                        .equalsIgnoreCase(
                                name
                        )) {


                    signals.hasListUnsubscribePost =
                            true;
                }
            }
        }


        parseSender(
                fromValue,
                signals
        );


        String normalizedSender =
                normalizeSignalText(
                        fromValue
                );


        signals.automatedSender =
                containsSignal(
                        normalizedSender,
                        "noreply"
                ) ||
                        containsSignal(
                                normalizedSender,
                                "no reply"
                        ) ||
                        containsSignal(
                                normalizedSender,
                                "do not reply"
                        ) ||
                        containsSignal(
                                normalizedSender,
                                "donotreply"
                        ) ||
                        containsSignal(
                                normalizedSender,
                                "notification"
                        ) ||
                        containsSignal(
                                normalizedSender,
                                "notifications"
                        ) ||
                        containsSignal(
                                normalizedSender,
                                "alert"
                        ) ||
                        containsSignal(
                                normalizedSender,
                                "alerts"
                        ) ||
                        containsSignal(
                                normalizedSender,
                                "statement"
                        ) ||
                        containsSignal(
                                normalizedSender,
                                "statements"
                        );


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
                angleAddressPattern.matcher(
                        fromValue
                );


        String emailAddress;


        if (angleMatcher.find()) {


            emailAddress =
                    angleMatcher.group(
                            1
                    );


            String displayName =
                    fromValue
                            .substring(
                                    0,
                                    angleMatcher.start()
                            )
                            .trim();


            signals.displayName =
                    removeOuterQuotes(
                            displayName
                    );


        } else {


            Pattern plainAddressPattern =
                    Pattern.compile(
                            "\\b[^\\s<>@]+@[^\\s<>@]+\\b"
                    );


            Matcher plainMatcher =
                    plainAddressPattern.matcher(
                            fromValue
                    );


            if (!plainMatcher.find()) {


                return;
            }


            emailAddress =
                    plainMatcher.group();


            signals.displayName =
                    "";
        }


        emailAddress =
                emailAddress
                        .toLowerCase(
                                Locale.ROOT
                        )
                        .trim();


        signals.emailAddress =
                emailAddress;


        int atIndex =
                emailAddress
                        .lastIndexOf(
                                '@'
                        );


        if (atIndex < 0 ||
                atIndex ==
                        emailAddress.length() - 1) {


            return;
        }


        signals.domain =
                emailAddress.substring(
                        atIndex + 1
                );


        signals.baseDomain =
                extractBaseDomain(
                        signals.domain
                );
    }


    // ============================================================
    // REMOVE OUTER QUOTES
    // ============================================================

    private String removeOuterQuotes(
            String value) {


        String result =
                value.trim();


        if (result.length() >= 2) {


            char first =
                    result.charAt(
                            0
                    );


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

    private String extractBaseDomain(
            String domain) {


        if (domain == null ||
                domain.isBlank()) {


            return "";
        }


        String normalizedDomain =
                domain
                        .toLowerCase(
                                Locale.ROOT
                        )
                        .trim();


        String[] parts =
                normalizedDomain
                        .split(
                                "\\."
                        );


        if (parts.length <= 2) {


            return normalizedDomain;
        }


        String last =
                parts[
                        parts.length - 1
                        ];


        String secondLast =
                parts[
                        parts.length - 2
                        ];


        if (last.length() == 2 &&
                ("co".equals(
                        secondLast
                ) ||
                        "com".equals(
                                secondLast
                        ) ||
                        "net".equals(
                                secondLast
                        ) ||
                        "org".equals(
                                secondLast
                        ) ||
                        "gov".equals(
                                secondLast
                        ))) {


            if (parts.length >= 3) {


                return parts[
                        parts.length - 3
                        ]
                        + "."
                        + secondLast
                        + "."
                        + last;
            }
        }


        return secondLast
                + "."
                + last;
    }


    // ============================================================
    // NORMALIZE SIGNAL TEXT
    // ============================================================

    private String normalizeSignalText(
            String value) {


        if (value == null) {


            return "";
        }


        return value
                .toLowerCase(
                        Locale.ROOT
                )
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
                normalizeSignalText(
                        term
                );


        if (normalizedText.isBlank() ||
                normalizedTerm.isBlank()) {


            return false;
        }


        String paddedText =
                " "
                        + normalizedText
                        + " ";


        String paddedTerm =
                " "
                        + normalizedTerm
                        + " ";


        return paddedText.contains(
                paddedTerm
        );
    }


    // ============================================================
    // MOVE GMAIL MESSAGE TO TRASH
    // ============================================================

    private void trashMessage(
            Gmail gmail,
            Message message) {


        if (message == null ||
                message.getId() == null ||
                message.getId().isBlank()) {


            return;
        }


        try {


            String messageId =
                    message.getId();


            ModifyMessageRequest request =
                    new ModifyMessageRequest()
                            .setAddLabelIds(
                                    List.of(
                                            "TRASH"
                                    )
                            )
                            .setRemoveLabelIds(
                                    List.of(
                                            "INBOX"
                                    )
                            );


            modifyMessageWithRetry(
                    gmail,
                    messageId,
                    request
            );


            System.out.println(
                    "SmartMail: Backlog processor moved Gmail message "
                            + "to Trash: "
                            + messageId
            );


        } catch (Exception e) {


            System.err.println(
                    "SmartMail: Backlog processor could not trash Gmail message "
                            + message.getId()
                            + ": "
                            + e.getMessage()
            );
        }
    }


    // ============================================================
    // SIGNAL CONTAINER
    // ============================================================

    private static class EmailSignals {


        private String displayName =
                "";


        private String emailAddress =
                "";


        private String domain =
                "";


        private String baseDomain =
                "";


        private boolean hasListUnsubscribe;


        private boolean hasListUnsubscribePost;


        private boolean bulkMail;


        private boolean automatedSender;
    }
}