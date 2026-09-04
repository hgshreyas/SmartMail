package com.smartmail.backend.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.smartmail.backend.model.Email;
import com.smartmail.backend.repository.EmailRepository;

import org.springframework.stereotype.Service;

@Service
public class EmailClassifierService {

    private final OllamaClassificationService ollamaClassificationService;
    private final EmailRepository emailRepository;

    /*
     * Keeps track of emails whose AI review is currently running.
     *
     * This prevents duplicate Ollama requests if the Gmail page is
     * refreshed while the first AI request is still running.
     */
    private final Set<String> aiReviewsInProgress =
            ConcurrentHashMap.newKeySet();

    public EmailClassifierService(
            OllamaClassificationService ollamaClassificationService,
            EmailRepository emailRepository) {

        this.ollamaClassificationService = ollamaClassificationService;
        this.emailRepository = emailRepository;
    }

    public Email classify(Email email) {
        return classify(
                email,
                false,
                false,
                false,
                false,
                "",
                "",
                ""
        );
    }

    public Email classify(
            Email email,
            boolean hasListUnsubscribe,
            boolean hasListUnsubscribePost,
            boolean bulkMail,
            boolean automatedSender,
            String displayName,
            String domain,
            String baseDomain) {

        /*
         * IMPORTANT:
         *
         * If this email has already been AI reviewed, do not run
         * rule-based classification again and do not overwrite the
         * AI result.
         *
         * This is what prevents page refreshes from changing an
         * already completed classification.
         */
        if (email.isAiReviewed()) {

            System.out.println(
                    "SmartMail: Skipping classification because email " +
                            "was already AI reviewed: " +
                            email.getGmailMessageId()
            );

            return email;
        }

        String subject = normalize(email.getSubject());
        String sender = normalize(email.getSender());

        String text = sender + " " + subject;

        int spamScore = 0;
        int importantScore = 0;
        int promotionalScore = 0;
        int otherScore = 0;

        List<String> importantReasons = new ArrayList<>();
        List<String> promotionalReasons = new ArrayList<>();
        List<String> spamReasons = new ArrayList<>();

        // ============================================================
        // SPAM
        // ============================================================

        if (containsTerm(text, "lottery winner")) {
            spamScore += 6;
            spamReasons.add("lottery winner");
        }

        if (containsTerm(text, "lottery prize")) {
            spamScore += 6;
            spamReasons.add("lottery prize");
        }

        if (containsTerm(text, "prize winner")) {
            spamScore += 6;
            spamReasons.add("prize winner");
        }

        if (containsTerm(text, "you have won")) {
            spamScore += 6;
            spamReasons.add("you have won");
        }

        if (containsTerm(text, "cash prize")) {
            spamScore += 5;
            spamReasons.add("cash prize");
        }

        if (containsTerm(text, "free money")) {
            spamScore += 6;
            spamReasons.add("free money");
        }

        if (containsTerm(text, "million dollars")) {
            spamScore += 6;
            spamReasons.add("million dollars");
        }

        if (containsTerm(text, "inheritance")) {
            spamScore += 5;
            spamReasons.add("inheritance");
        }

        if (containsTerm(text, "claim your prize")) {
            spamScore += 6;
            spamReasons.add("claim your prize");
        }

        if (containsTerm(text, "claim your reward")) {
            spamScore += 5;
            spamReasons.add("claim your reward");
        }

        // ============================================================
        // IMPORTANT - FINANCIAL
        // ============================================================

        if (containsTerm(text, "transaction")) {
            importantScore += 5;
            importantReasons.add("transaction");
        }

        if (containsTerm(text, "txn")) {
            importantScore += 5;
            importantReasons.add("transaction");
        }

        if (containsTerm(text, "upi")) {
            importantScore += 5;
            importantReasons.add("UPI");
        }

        if (containsTerm(text, "debited")) {
            importantScore += 5;
            importantReasons.add("debited");
        }

        if (containsTerm(text, "credited")) {
            importantScore += 5;
            importantReasons.add("credited");
        }

        if (containsTerm(text, "account statement")) {
            importantScore += 5;
            importantReasons.add("account statement");
        }

        if (containsTerm(text, "account alert")) {
            importantScore += 5;
            importantReasons.add("account alert");
        }

        if (containsTerm(text, "fund transfer")) {
            importantScore += 5;
            importantReasons.add("fund transfer");
        }

        if (containsTerm(text, "money transfer")) {
            importantScore += 5;
            importantReasons.add("money transfer");
        }

        if (containsTerm(text, "transfer successful")) {
            importantScore += 5;
            importantReasons.add("transfer successful");
        }

        if (containsTerm(text, "transaction successful")) {
            importantScore += 5;
            importantReasons.add("transaction successful");
        }

        if (containsTerm(text, "contract note")) {
            importantScore += 5;
            importantReasons.add("contract note");
        }

        if (containsTerm(text, "margin statement")) {
            importantScore += 5;
            importantReasons.add("margin statement");
        }

        if (containsTerm(text, "trade date")) {
            importantScore += 5;
            importantReasons.add("trade date");
        }

        if (containsTerm(text, "demat")) {
            importantScore += 5;
            importantReasons.add("demat");
        }

        if (containsTerm(text, "bank alert")) {
            importantScore += 5;
            importantReasons.add("bank alert");
        }

        // ============================================================
        // IMPORTANT - SECURITY
        // ============================================================

        if (containsTerm(text, "security alert")) {
            importantScore += 6;
            importantReasons.add("security alert");
        }

        if (containsTerm(text, "security notification")) {
            importantScore += 5;
            importantReasons.add("security notification");
        }

        if (containsTerm(text, "suspicious activity")) {
            importantScore += 6;
            importantReasons.add("suspicious activity");
        }

        if (containsTerm(text, "login attempt")) {
            importantScore += 6;
            importantReasons.add("login attempt");
        }

        if (containsTerm(text, "new sign in")) {
            importantScore += 6;
            importantReasons.add("new sign in");
        }

        if (containsTerm(text, "new login")) {
            importantScore += 6;
            importantReasons.add("new login");
        }

        if (containsTerm(text, "password changed")) {
            importantScore += 6;
            importantReasons.add("password changed");
        }

        if (containsTerm(text, "account security")) {
            importantScore += 5;
            importantReasons.add("account security");
        }

        if (containsTerm(text, "verify your identity")) {
            importantScore += 5;
            importantReasons.add("identity verification");
        }

        if (containsTerm(text, "one time password")) {
            importantScore += 6;
            importantReasons.add("OTP");
        }

        if (containsTerm(text, "otp")) {
            importantScore += 6;
            importantReasons.add("OTP");
        }

        // ============================================================
        // IMPORTANT - JOB / RECRUITMENT
        // ============================================================

        if (containsTerm(text, "job opportunity")) {
            importantScore += 4;
            importantReasons.add("job opportunity");
        }

        if (containsTerm(text, "career opportunity")) {
            importantScore += 4;
            importantReasons.add("career opportunity");
        }

        if (containsTerm(text, "job opening")) {
            importantScore += 4;
            importantReasons.add("job opening");
        }

        if (containsTerm(text, "job application")) {
            importantScore += 5;
            importantReasons.add("job application");
        }

        if (containsTerm(text, "application status")) {
            importantScore += 5;
            importantReasons.add("application status");
        }

        if (containsTerm(text, "interview invitation")) {
            importantScore += 5;
            importantReasons.add("interview invitation");
        }

        if (containsTerm(text, "interview")) {
            importantScore += 4;
            importantReasons.add("interview");
        }

        if (containsTerm(text, "hiring")) {
            importantScore += 4;
            importantReasons.add("hiring");
        }

        if (containsTerm(text, "internship opportunity")) {
            importantScore += 4;
            importantReasons.add("internship opportunity");
        }

        if (containsTerm(text, "campus placement")) {
            importantScore += 4;
            importantReasons.add("campus placement");
        }

        if (containsTerm(text, "recruiter")) {
            importantScore += 4;
            importantReasons.add("recruiter");
        }

        if (containsTerm(text, "recruitment")) {
            importantScore += 4;
            importantReasons.add("recruitment");
        }

        // ============================================================
        // IMPORTANT - OPPORTUNITIES
        // ============================================================

        if (containsTerm(text, "stipend")) {
            importantScore += 4;
            importantReasons.add("stipend");
        }

        if (containsTerm(text, "opportunity")) {
            importantScore += 4;
            importantReasons.add("opportunity");
        }

        if (containsTerm(text, "internship")) {
            importantScore += 4;
            importantReasons.add("internship");
        }

        if (containsTerm(text, "hackathon")) {
            importantScore += 4;
            importantReasons.add("hackathon");
        }

        if (containsTerm(text, "coding competition")) {
            importantScore += 4;
            importantReasons.add("coding competition");
        }

        if (containsTerm(text, "coding contest")) {
            importantScore += 4;
            importantReasons.add("coding contest");
        }

        if (containsTerm(text, "tech competition")) {
            importantScore += 4;
            importantReasons.add("tech competition");
        }

        if (containsTerm(text, "idea submission")) {
            importantScore += 4;
            importantReasons.add("idea submission");
        }

        // ============================================================
        // IMPORTANT - DEADLINES
        // ============================================================

        if (containsTerm(text, "deadline")) {
            importantScore += 4;
            importantReasons.add("deadline");
        }

        if (containsTerm(text, "due date")) {
            importantScore += 4;
            importantReasons.add("due date");
        }

        if (containsTerm(text, "expires")) {
            importantScore += 4;
            importantReasons.add("expires");
        }

        if (containsTerm(text, "registration closes")) {
            importantScore += 4;
            importantReasons.add("registration closes");
        }

        if (containsTerm(text, "submission deadline")) {
            importantScore += 5;
            importantReasons.add("submission deadline");
        }

        if (containsTerm(text, "project deadline")) {
            importantScore += 5;
            importantReasons.add("project deadline");
        }

        // ============================================================
        // IMPORTANT - ACADEMIC / WORK
        // ============================================================

        if (containsTerm(text, "assignment")) {
            importantScore += 3;
            importantReasons.add("assignment");
        }

        if (containsTerm(text, "exam")) {
            importantScore += 4;
            importantReasons.add("exam");
        }

        if (containsTerm(text, "examination")) {
            importantScore += 4;
            importantReasons.add("examination");
        }

        if (containsTerm(text, "course registration")) {
            importantScore += 4;
            importantReasons.add("course registration");
        }

        if (containsTerm(text, "meeting invitation")) {
            importantScore += 3;
            importantReasons.add("meeting invitation");
        }

        if (containsTerm(text, "meeting request")) {
            importantScore += 3;
            importantReasons.add("meeting request");
        }

        // ============================================================
        // PROMOTIONAL
        // ============================================================

        if (containsTerm(text, "special offer")) {
            promotionalScore += 4;
            promotionalReasons.add("special offer");
        }

        if (containsTerm(text, "exclusive offer")) {
            promotionalScore += 4;
            promotionalReasons.add("exclusive offer");
        }

        if (containsTerm(text, "limited time offer")) {
            promotionalScore += 5;
            promotionalReasons.add("limited time offer");
        }

        if (containsTerm(text, "discount")) {
            promotionalScore += 4;
            promotionalReasons.add("discount");
        }

        if (containsTerm(text, "coupon")) {
            promotionalScore += 4;
            promotionalReasons.add("coupon");
        }

        if (containsTerm(text, "on sale")) {
            promotionalScore += 4;
            promotionalReasons.add("sale");
        }

        if (containsTerm(text, "promotion")) {
            promotionalScore += 4;
            promotionalReasons.add("promotion");
        }

        if (containsTerm(text, "promotional")) {
            promotionalScore += 4;
            promotionalReasons.add("promotional");
        }

        if (containsTerm(text, "shopping offer")) {
            promotionalScore += 4;
            promotionalReasons.add("shopping offer");
        }

        if (containsTerm(text, "cashback offer")) {
            promotionalScore += 4;
            promotionalReasons.add("cashback offer");
        }

        if (containsTerm(sender, "newsletter")) {
            promotionalScore += 3;
            promotionalReasons.add("newsletter sender");
        }

        if (containsTerm(subject, "newsletter")) {
            promotionalScore += 3;
            promotionalReasons.add("newsletter");
        }

        if (containsTerm(text, "survey invitation")) {
            promotionalScore += 4;
            promotionalReasons.add("survey");
        }

        if (containsTerm(text, "market research")) {
            promotionalScore += 3;
            promotionalReasons.add("market research");
        }

        if (containsTerm(text, "participate in a survey")) {
            promotionalScore += 4;
            promotionalReasons.add("survey");
        }

        if (containsTerm(text, "advertisement")) {
            promotionalScore += 4;
            promotionalReasons.add("advertisement");
        }

        if (containsTerm(text, "advertising")) {
            promotionalScore += 4;
            promotionalReasons.add("advertising");
        }

        if (containsTerm(text, "marketing email")) {
            promotionalScore += 4;
            promotionalReasons.add("marketing email");
        }

        if (containsTerm(text, "marketing communication")) {
            promotionalScore += 4;
            promotionalReasons.add("marketing communication");
        }

        if (containsTerm(sender, "pokemongo") ||
                containsTerm(sender, "pokemon go") ||
                containsTerm(sender, "thegamer") ||
                containsTerm(sender, "gaming newsletter")) {

            promotionalScore += 4;
            promotionalReasons.add("gaming/entertainment");
        }

        if (containsTerm(subject, "game update") ||
                containsTerm(subject, "game news") ||
                containsTerm(subject, "game rush")) {

            promotionalScore += 4;
            promotionalReasons.add("gaming update");
        }

        if (containsTerm(subject, "is back")) {
            promotionalScore += 2;
            promotionalReasons.add("entertainment update");
        }

        if (containsTerm(sender, "academia mail") ||
                containsTerm(sender, "academia mentions") ||
                containsTerm(subject, "someone mentioned this name")) {

            promotionalScore += 4;
            promotionalReasons.add("content notification");
        }

        // ============================================================
        // AUTOMATED SENDER
        // ============================================================

        if (containsTerm(sender, "noreply") ||
                containsTerm(sender, "no reply") ||
                containsTerm(sender, "do not reply") ||
                containsTerm(sender, "notification") ||
                containsTerm(sender, "notifications") ||
                containsTerm(sender, "newsletter")) {

            promotionalScore += 1;
            promotionalReasons.add("automated sender");
        }

        // ============================================================
        // SPECIAL CASES
        // ============================================================

        if (containsTerm(sender, "sbi bank in") &&
                containsTerm(subject, "alert")) {

            importantScore += 5;
            importantReasons.add("SBI bank alert");
        }

        if (containsTerm(sender, "unstop") &&
                (containsTerm(subject, "stipend") ||
                        containsTerm(subject, "earn") ||
                        containsTerm(subject, "opportunity"))) {

            importantScore += 5;
            importantReasons.add("Unstop opportunity");
        }

        // ============================================================
        // STRUCTURED GMAIL SIGNALS
        // ============================================================

        String normalizedDisplayName = normalize(displayName);
        String normalizedDomain = normalize(domain);
        String normalizedBaseDomain = normalize(baseDomain);

        if (bulkMail ||
                hasListUnsubscribe ||
                hasListUnsubscribePost) {

            promotionalScore += 2;
            promotionalReasons.add("bulk/unsubscribe metadata");
        }

        if (automatedSender) {
            promotionalScore += 1;
            promotionalReasons.add("Gmail automated-sender signal");
        }

        if (containsTerm(normalizedDisplayName, "newsletter") ||
                containsTerm(normalizedDisplayName, "survey") ||
                containsTerm(normalizedDisplayName, "offers") ||
                containsTerm(normalizedDisplayName, "marketing") ||
                containsTerm(normalizedDisplayName, "promotions") ||
                containsTerm(normalizedDisplayName, "deals")) {

            promotionalScore += 3;
            promotionalReasons.add("sender marketing intent");
        }

        if (containsTerm(normalizedDisplayName, "alert") ||
                containsTerm(normalizedDisplayName, "security") ||
                containsTerm(normalizedDisplayName, "support")) {

            importantScore += 2;
            importantReasons.add("sender attention intent");
        }

        if (containsTerm(normalizedDomain, "marketing") ||
                containsTerm(normalizedDomain, "promo") ||
                containsTerm(normalizedDomain, "offers") ||
                containsTerm(normalizedDomain, "newsletter")) {

            promotionalScore += 2;
            promotionalReasons.add("marketing domain signal");
        }

        if (importantScore > 0 &&
                (containsTerm(text, "transaction") ||
                        containsTerm(text, "debited") ||
                        containsTerm(text, "credited") ||
                        containsTerm(text, "upi") ||
                        containsTerm(text, "demat") ||
                        containsTerm(text, "security alert") ||
                        containsTerm(text, "suspicious activity") ||
                        containsTerm(text, "login attempt") ||
                        containsTerm(text, "otp") ||
                        containsTerm(text, "new sign in"))) {

            importantScore += 3;
            importantReasons.add("structured-signal safety bias");
        }

        if (!normalizedBaseDomain.isEmpty()) {
            System.out.println(
                    "SmartMail sender domain: " +
                            normalizedBaseDomain
            );
        }

        // ============================================================
        // WINNING CATEGORY
        // ============================================================

        int maxScore = Math.max(
                Math.max(spamScore, importantScore),
                Math.max(promotionalScore, otherScore)
        );

        String category;

        if (maxScore == 0) {
            category = "OTHER";
        }
        else if (spamScore == maxScore) {
            category = "SPAM";
        }
        else if (importantScore == maxScore) {
            category = "IMPORTANT";
        }
        else if (promotionalScore == maxScore) {
            category = "PROMOTIONAL";
        }
        else {
            category = "OTHER";
        }

        // ============================================================
        // CONFLICT
        // ============================================================

        int secondHighest = getSecondHighest(
                spamScore,
                importantScore,
                promotionalScore,
                otherScore
        );

        boolean conflicting =
                maxScore > 0 &&
                        secondHighest > 0 &&
                        (maxScore - secondHighest <= 1);

        // ============================================================
        // CONFIDENCE
        // ============================================================

        double confidence;

        if (maxScore == 0) {
            confidence = 0.40;
        }
        else if (conflicting) {
            confidence = 0.55;
        }
        else if (maxScore >= 8) {
            confidence = 0.97;
        }
        else if (maxScore >= 6) {
            confidence = 0.93;
        }
        else if (maxScore >= 4) {
            confidence = 0.85;
        }
        else {
            confidence = 0.70;
        }

        // ============================================================
        // ACTION
        // ============================================================

        boolean needsAiReview =
                confidence < 0.90 ||
                        conflicting ||
                        "OTHER".equals(category);

        String action;

        if (needsAiReview) {

            action = "PENDING_REVIEW";

        }
        else if ("SPAM".equals(category) ||
                "PROMOTIONAL".equals(category)) {

            /*
             * High-confidence spam/promotional emails should be
             * permanently handled by the Gmail TRASH operation.
             */
            action = "TRASH";

        }
        else {

            /*
             * High-confidence important emails stay in inbox.
             */
            action = "KEEP";
        }

        // ============================================================
        // SAVE
        // ============================================================

        email.setCategory(category);
        email.setConfidence(confidence);
        email.setAction(action);
        email.setProcessed(true);

        /*
         * A high-confidence rule-based decision does NOT need Ollama.
         *
         * Therefore it is already considered reviewed.
         */
        if (!needsAiReview) {

            email.setAiReviewed(true);

        }
        else {

            email.setAiReviewed(false);
        }

        System.out.println(
                "SmartMail classification: " +
                        "category=" + category +
                        ", confidence=" + confidence +
                        ", action=" + action +
                        ", aiReviewed=" + email.isAiReviewed() +
                        ", spamScore=" + spamScore +
                        ", importantScore=" + importantScore +
                        ", promotionalScore=" + promotionalScore +
                        ", importantReasons=" + importantReasons +
                        ", promotionalReasons=" + promotionalReasons +
                        ", spamReasons=" + spamReasons
        );

        return email;
    }

    // ================================================================
    // ASYNC AI REVIEW
    // ================================================================

    public void classifyWithAiAsync(
            Email email,
            boolean hasListUnsubscribe,
            boolean hasListUnsubscribePost,
            boolean bulkMail,
            boolean automatedSender,
            String domain,
            Consumer<Email> actionHandler) {

        String messageId = email.getGmailMessageId();

        /*
         * Already completed.
         */
        if (email.isAiReviewed()) {

            System.out.println(
                    "SmartMail: Skipping AI review because email was " +
                            "already reviewed: " +
                            messageId
            );

            return;
        }

        /*
         * No Gmail message ID means we cannot safely prevent
         * duplicate processing.
         */
        if (messageId == null || messageId.isBlank()) {

            System.out.println(
                    "SmartMail: Cannot start AI review because " +
                            "Gmail message ID is missing."
            );

            return;
        }

        /*
         * IMPORTANT:
         *
         * add() returns false when this message is already present.
         *
         * This prevents two simultaneous Ollama calls for the
         * same Gmail message.
         */
        if (!aiReviewsInProgress.add(messageId)) {

            System.out.println(
                    "SmartMail: AI review already in progress. " +
                            "Skipping duplicate request for " +
                            messageId
            );

            return;
        }

        System.out.println(
                "SmartMail: Starting async Ollama review for " +
                        messageId
        );

        CompletableFuture<String> future;

        try {

            future = ollamaClassificationService.classifyAsync(
                    email.getSender(),
                    normalize(domain),
                    email.getSubject(),
                    createBodyExcerpt(email.getBody()),
                    hasListUnsubscribe,
                    hasListUnsubscribePost,
                    bulkMail,
                    automatedSender
            );

        }
        catch (Exception e) {

            aiReviewsInProgress.remove(messageId);

            System.err.println(
                    "SmartMail: Failed to start AI review for " +
                            messageId +
                            ": " +
                            e.getMessage()
            );

            return;
        }

        future.thenAccept(aiResult -> {

            try {

                AiResult parsedResult =
                        parseAiResult(aiResult);

                if (parsedResult == null ||
                        !isValidCategory(parsedResult.category) ||
                        parsedResult.confidence < 0.0 ||
                        parsedResult.confidence > 1.0) {

                    System.out.println(
                            "SmartMail Ollama async review returned " +
                                    "invalid JSON. Keeping PENDING_REVIEW " +
                                    "for " +
                                    messageId
                    );

                    return;
                }

                emailRepository.findByGmailMessageId(
                        messageId
                ).ifPresentOrElse(

                        savedEmail -> {

                            savedEmail.setCategory(
                                    parsedResult.category
                            );

                            savedEmail.setConfidence(
                                    parsedResult.confidence
                            );

                            // ============================================
                            // FINAL AI ACTION POLICY
                            // ============================================
                            //
                            // IMPORTANT    -> KEEP
                            // PROMOTIONAL  -> TRASH
                            // SPAM         -> TRASH
                            // UNCERTAIN    -> PENDING_REVIEW
                            //
                            // ============================================

                            String finalAction;

                            if (parsedResult.confidence >= 0.90 &&
                                    "SPAM".equals(
                                            parsedResult.category)) {

                                finalAction = "TRASH";

                            }
                            else if (parsedResult.confidence >= 0.90 &&
                                    "PROMOTIONAL".equals(
                                            parsedResult.category)) {

                                finalAction = "TRASH";

                            }
                            else if (parsedResult.confidence >= 0.80 &&
                                    "IMPORTANT".equals(
                                            parsedResult.category)) {

                                finalAction = "KEEP";

                            }
                            else {

                                finalAction = "PENDING_REVIEW";
                            }

                            savedEmail.setAction(finalAction);
                            savedEmail.setProcessed(true);

                            /*
                             * AI has completed its review even when
                             * the result is uncertain.
                             *
                             * This prevents Ollama from being called
                             * again after page refresh.
                             */
                            savedEmail.setAiReviewed(true);

                            Email saved =
                                    emailRepository.save(savedEmail);

                            System.out.println(
                                    "SmartMail Ollama async review " +
                                            "completed: " +
                                            "messageId=" +
                                            saved.getGmailMessageId() +
                                            ", category=" +
                                            parsedResult.category +
                                            ", confidence=" +
                                            parsedResult.confidence +
                                            ", action=" +
                                            finalAction +
                                            ", aiReviewed=" +
                                            saved.isAiReviewed() +
                                            ", reason=" +
                                            parsedResult.reason
                            );

                            // ============================================
                            // EXECUTE GMAIL ACTION
                            // ============================================

                            if (actionHandler != null &&
                                    "TRASH".equalsIgnoreCase(
                                            finalAction)) {

                                try {

                                    actionHandler.accept(saved);

                                }
                                catch (Exception actionException) {

                                    System.err.println(
                                            "SmartMail: Failed to execute " +
                                                    "AI trash action for " +
                                                    saved.getGmailMessageId()
                                    );

                                    actionException.printStackTrace();
                                }
                            }
                        },

                        () -> {

                            System.out.println(
                                    "SmartMail: Could not find email " +
                                            "in database after AI review: " +
                                            messageId
                            );
                        }
                );

            }
            catch (Exception e) {

                System.err.println(
                        "SmartMail Ollama async review failed for " +
                                messageId +
                                ": " +
                                e.getMessage()
                );

                e.printStackTrace();

            }
            finally {

                /*
                 * Allow future processing only if the email still
                 * needs review.
                 *
                 * Normally aiReviewed=true after successful AI
                 * processing, so future page refreshes will still
                 * skip it.
                 */
                aiReviewsInProgress.remove(messageId);
            }

        }).exceptionally(error -> {

            /*
             * Ollama/API failure.
             *
             * The email remains PENDING_REVIEW, and because we
             * remove it from the in-progress set, a future attempt
             * is allowed.
             */
            aiReviewsInProgress.remove(messageId);

            System.err.println(
                    "SmartMail: Ollama async request failed for " +
                            messageId +
                            ": " +
                            error.getMessage()
            );

            return null;
        });
    }

    // ================================================================
    // SECOND HIGHEST SCORE
    // ================================================================

    private int getSecondHighest(
            int spam,
            int important,
            int promotional,
            int other) {

        int highest = Math.max(
                Math.max(spam, important),
                Math.max(promotional, other)
        );

        int second = 0;

        int[] scores = {
                spam,
                important,
                promotional,
                other
        };

        for (int score : scores) {

            if (score < highest && score > second) {
                second = score;
            }
        }

        return second;
    }

    // ================================================================
    // NORMALIZATION
    // ================================================================

    private String normalize(String value) {

        if (value == null) {
            return "";
        }

        String normalized = Normalizer.normalize(
                value,
                Normalizer.Form.NFKD
        );

        normalized = normalized.replaceAll(
                "\\p{M}",
                ""
        );

        normalized = normalized.toLowerCase(
                Locale.ROOT
        );

        normalized = normalized.replaceAll(
                "[^\\p{L}\\p{N}]+",
                " "
        );

        normalized = normalized.trim()
                .replaceAll("\\s+", " ");

        return normalized;
    }

    // ================================================================
    // AI BODY EXCERPT
    // ================================================================

    private String createBodyExcerpt(String body) {

        String normalizedBody =
                body == null ? "" : body.trim();

        if (normalizedBody.length() > 4000) {

            return normalizedBody.substring(
                    0,
                    4000
            );
        }

        return normalizedBody;
    }

    // ================================================================
    // PARSE AI RESULT
    // ================================================================

    private AiResult parseAiResult(String json) {

        if (json == null || json.isBlank()) {
            return null;
        }

        String cleaned = json.trim();

        if (cleaned.startsWith("```")) {

            cleaned = cleaned.replaceFirst(
                    "^```(?:json)?\\s*",
                    ""
            );

            cleaned = cleaned.replaceFirst(
                    "\\s*```$",
                    ""
            );

            cleaned = cleaned.trim();
        }

        Pattern categoryPattern =
                Pattern.compile(
                        "\"category\"\\s*:\\s*\"([^\"]+)\"",
                        Pattern.CASE_INSENSITIVE
                );

        Pattern confidencePattern =
                Pattern.compile(
                        "\"confidence\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)",
                        Pattern.CASE_INSENSITIVE
                );

        Pattern reasonPattern =
                Pattern.compile(
                        "\"reason\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
                        Pattern.CASE_INSENSITIVE
                );

        Matcher categoryMatcher =
                categoryPattern.matcher(cleaned);

        Matcher confidenceMatcher =
                confidencePattern.matcher(cleaned);

        Matcher reasonMatcher =
                reasonPattern.matcher(cleaned);

        if (!categoryMatcher.find() ||
                !confidenceMatcher.find()) {

            return null;
        }

        String category =
                categoryMatcher.group(1)
                        .trim()
                        .toUpperCase(Locale.ROOT);

        double confidence;

        try {

            confidence = Double.parseDouble(
                    confidenceMatcher.group(1)
            );

        }
        catch (NumberFormatException e) {

            return null;
        }

        String reason = "";

        if (reasonMatcher.find()) {

            reason = reasonMatcher.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\n", " ")
                    .replace("\\\\", "\\");
        }

        return new AiResult(
                category,
                confidence,
                reason
        );
    }

    // ================================================================
    // VALID CATEGORY
    // ================================================================

    private boolean isValidCategory(String category) {

        return "IMPORTANT".equals(category) ||
                "PROMOTIONAL".equals(category) ||
                "SPAM".equals(category);
    }

    private static class AiResult {

        private final String category;
        private final double confidence;
        private final String reason;

        private AiResult(
                String category,
                double confidence,
                String reason) {

            this.category = category;
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    // ================================================================
    // WHOLE TERM MATCHING
    // ================================================================

    private boolean containsTerm(
            String normalizedText,
            String term) {

        String normalizedTerm =
                normalize(term);

        if (normalizedText.isEmpty() ||
                normalizedTerm.isEmpty()) {

            return false;
        }

        String paddedText =
                " " + normalizedText + " ";

        String paddedTerm =
                " " + normalizedTerm + " ";

        return paddedText.contains(
                paddedTerm
        );
    }
}