# SmartMail 📧🤖

SmartMail is an AI-powered Gmail management system that automatically analyzes, classifies, and organizes emails using a combination of deterministic rules and a local Large Language Model.

The application combines **Spring Boot**, **React + Vite**, **PostgreSQL**, the **Google Gmail API**, and **Ollama** to provide an end-to-end intelligent email-management workflow.

---

## 🚀 Features

### 🔐 Google OAuth2 Authentication

- Secure Google authentication using OAuth2.
- Uses Gmail API authorization instead of storing Gmail passwords.
- Requests Gmail Modify permission for email-management actions.
- Supports refresh tokens for long-running Gmail operations.

---

### 📬 Gmail Integration

SmartMail communicates directly with Gmail using the Google Gmail API.

It can:

- Fetch Gmail inbox messages.
- Process the complete inbox using Gmail pagination.
- Extract sender, subject, body, Gmail message ID, and metadata.
- Detect useful Gmail headers such as mailing-list information.
- Modify Gmail labels.
- Move unwanted emails to Gmail Trash.
- Retry operations when Gmail rate limits are encountered.

Large inboxes are processed sequentially in controlled batches to avoid overwhelming the Gmail API.

---

## 🧠 Hybrid Classification Pipeline

SmartMail uses a **rules-first architecture**.

```text
Gmail Email
    │
    ▼
Deterministic Rules
    │
    ├── Clear IMPORTANT ─────► KEEP
    │
    ├── Clear PROMOTIONAL ───► TRASH
    │
    ├── Clear SPAM ──────────► TRASH
    │
    └── Uncertain
           │
           ▼
     PENDING_REVIEW
           │
           ▼
      Ollama / AI
           │
           ├── Confident decision
           │       ├── KEEP
           │       └── TRASH
           │
           └── Still uncertain
                   │
                   ▼
              Human Review
```

This design avoids sending every Gmail message to the local AI model.

Emails that can be confidently handled using deterministic rules are processed immediately.

Only uncertain emails are sent to Ollama.

---

## 🏷️ Email Categories

SmartMail classifies emails into three primary categories:

| Category | Description |
|---|---|
| **IMPORTANT** | Banking, transactions, security alerts, recruitment, jobs, internships, hackathons, deadlines, academic or important communication |
| **PROMOTIONAL** | Marketing, advertising, newsletters, offers, surveys and promotional campaigns |
| **SPAM** | Suspicious lottery, prize, fraudulent or deceptive messages |

An email that cannot be handled safely is assigned the action:

```text
PENDING_REVIEW
```

---

## ⚡ Rules-First Processing

The deterministic classifier handles obvious cases before AI is used.

Examples of important signals include:

- Banking transactions
- UPI alerts
- Account statements
- Security alerts
- Login notifications
- OTP messages
- Recruitment emails
- Interview invitations
- Internship opportunities
- Hackathons
- Registration deadlines

Examples of promotional signals include:

- Marketing campaigns
- Newsletters
- Advertisements
- Surveys
- Offers and discounts
- Mailing-list headers

Examples of spam signals include:

- Lottery winner messages
- Fake prizes
- Cash-prize scams
- Fraudulent reward messages

This reduces AI usage and improves processing speed.

---

## 🤖 Local AI Classification

SmartMail uses:

```text
Ollama
llama3.2:1b
```

The AI analyzes:

- Sender
- Sender domain
- Subject
- Email body
- Mailing-list signals
- Automated-sender signals
- Overall purpose of the email

The model returns structured information containing:

```text
category
confidence
reason
```

Possible AI categories are:

```text
IMPORTANT
PROMOTIONAL
SPAM
```

---

## 🧵 Sequential AI Queue

Local AI processing can be resource-intensive.

SmartMail therefore uses a dedicated sequential Ollama worker.

```text
Pending Email 1
      │
      ▼
    Ollama
      │
      ▼
Pending Email 2
      │
      ▼
    Ollama
      │
      ▼
Pending Email 3
```

Only **one Ollama request runs at a time**.

A complete pending queue can be started in a single run while emails are still processed sequentially.

This prevents hundreds of AI requests from running simultaneously.

---

## 👤 Human Review

If an email cannot be classified safely, it remains:

```text
PENDING_REVIEW
```

The React dashboard allows the user to manually choose:

- **Keep**
- **Trash**

This provides a human safety layer for uncertain classifications.

---

## 🗑️ Gmail Actions

SmartMail supports real Gmail actions.

### KEEP

The email remains in the Gmail inbox.

### TRASH

SmartMail modifies Gmail labels and moves the message to Gmail Trash.

### PENDING_REVIEW

No automatic destructive Gmail action is taken until the classification is resolved.

---

## 🔄 Duplicate Prevention

SmartMail prevents unnecessary repeated processing.

Each email stores its Gmail message ID in PostgreSQL.

The system also tracks whether AI has already reviewed the message.

```text
Gmail Message
      │
      ▼
Already Stored?
      │
      ├── Yes ──► Reuse existing record
      │
      └── No ───► Store message
```

For AI processing:

```text
Pending Email
      │
      ▼
Already AI Reviewed?
      │
      ├── Yes ──► Do not resend to Ollama
      │
      └── No
           │
           ▼
      AI In Progress?
           │
           ├── Yes ──► Skip duplicate request
           │
           └── No ───► Process
```

This helps keep processing idempotent.

---

## 📊 Background Processing & Progress

Large Gmail operations run in the background.

SmartMail exposes progress information including:

```text
processing
stage
total
completed
failed
submitted
backlogAtStart
```

Example stages include:

```text
FETCHING_GMAIL
PROCESSING_EMAILS
RULE_FIRST_REVIEW
PROCESSING_AI_EMAILS
GMAIL_RATE_LIMIT_BACKOFF
COMPLETE
```

The frontend displays processing status without requiring the browser to remain blocked during long-running operations.

---

## 📬 Full Inbox Processing

SmartMail supports Gmail pagination and can scan an inbox containing thousands of messages.

The Gmail processing pipeline:

```text
Google OAuth
     │
     ▼
Gmail API
     │
     ▼
Fetch Inbox Pages
     │
     ▼
Sequential Processing Batches
     │
     ▼
Deterministic Classification
     │
     ├── KEEP
     ├── TRASH
     └── PENDING_REVIEW
```

Messages are processed in controlled batches rather than loading and modifying everything concurrently.

---

## ⏳ Gmail Rate-Limit Protection

Google Gmail API usage is rate limited.

SmartMail includes:

- Sequential Gmail API requests
- Per-message pacing
- Batch pacing
- Automatic rate-limit detection
- Retry logic
- Backoff before retrying

This allows large Gmail inboxes to be processed more reliably.

---

## 🔑 OAuth Token Refresh

Large Gmail inbox processing can take longer than the lifetime of a single OAuth access token.

SmartMail therefore requests offline access and supports Google refresh tokens.

This allows long-running Gmail operations to refresh authentication automatically instead of failing when the initial access token expires.

---

## 🖥️ Frontend Dashboard

SmartMail includes a React + Vite dashboard.

The dashboard supports:

- Email listing
- Search
- Category filtering
- Action filtering
- Email statistics
- Backend online/offline status
- AI queue count
- Human-review count
- Processing status
- Email detail modal
- HTML email rendering
- Manual Keep
- Manual Trash
- Manual refresh

---

## 📊 Dashboard Status

The interface separates uncertain email states into:

### AI Queue

```text
PENDING_REVIEW
+
aiReviewed = false
```

These emails still need AI processing.

### Human Review

```text
PENDING_REVIEW
+
aiReviewed = true
```

These emails have already been examined by AI but still require a user decision.

---

## 🗃️ PostgreSQL Persistence

SmartMail stores processed email information in PostgreSQL.

Stored information includes:

- Gmail message ID
- Sender
- Subject
- Email body
- Category
- Confidence
- Recommended action
- Processing state
- AI review state

PostgreSQL allows SmartMail to maintain processing state across application restarts.

---

## 🏗️ System Architecture

```text
                         Gmail
                           │
                           ▼
                   Google Gmail API
                           │
                           ▼
                  Spring Boot Backend
                           │
             ┌─────────────┴─────────────┐
             │                           │
             ▼                           ▼
       Gmail Service              PostgreSQL
             │
             ▼
   Deterministic Classifier
             │
       ┌─────┴─────┐
       │           │
   Resolved     Uncertain
       │           │
       ▼           ▼
 KEEP / TRASH   PENDING_REVIEW
                   │
                   ▼
          Pending Review Service
                   │
                   ▼
              Ollama AI
                   │
          ┌────────┴────────┐
          │                 │
       Resolved          Uncertain
          │                 │
          ▼                 ▼
     KEEP / TRASH       Human Review
                            │
                            ▼
                     React Dashboard
```

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Programming Language | Java 25 |
| Backend | Spring Boot 4.1.1 |
| Build Tool | Maven |
| Security | Spring Security |
| Authentication | Google OAuth2 |
| Email Integration | Google Gmail API |
| Persistence | Spring Data JPA |
| Database | PostgreSQL |
| Local AI | Ollama |
| LLM | Llama 3.2 1B |
| Frontend | React |
| Frontend Tooling | Vite |
| Web Technologies | HTML, CSS, JavaScript |

---

## 📁 Project Structure

```text
SmartMail/
│
├── backend/
│   ├── src/
│   │   └── main/
│   │       ├── java/
│   │       │   └── com/smartmail/backend/
│   │       │       ├── config/
│   │       │       ├── controller/
│   │       │       ├── model/
│   │       │       ├── repository/
│   │       │       └── service/
│   │       │
│   │       └── resources/
│   │           ├── application.properties
│   │           └── application-local.properties
│   │
│   └── pom.xml
│
├── frontend/
│   ├── src/
│   ├── public/
│   ├── package.json
│   └── vite.config.js
│
├── .gitignore
└── README.md
```

`application-local.properties` contains local environment configuration and is excluded from Git.

---

## 🔐 Security

SmartMail uses Google OAuth2 instead of Gmail passwords.

### Multi-user account isolation

- Google OpenID Connect `sub` is the stable account identity.
- Each email row is linked to one SmartMail account; email reads, actions, and pending review queries are scoped to that owner.
- Existing local email rows keep a nullable owner and are retained, but are not returned to signed-in users. Newly fetched rows are owner-linked.
- For production, set the Render environment variable `SMARTMAIL_FRONTEND_URL` to `https://smart-mail-wheat.vercel.app` so OAuth returns to the deployed frontend. Local development defaults to `http://localhost:5173`.
- The frontend sends a CSRF token for POST and DELETE operations. Keep the configured stable frontend origin in the backend CORS allowlist.

Before describing a deployment as multi-user ready, verify with two separate Google accounts that each sees only its own emails, and that an account cannot fetch, keep, trash, or delete another account's email ID. Also verify that `/emails/gmail/results` returns `401` when signed out.

Sensitive values such as:

- Google OAuth client ID
- Google OAuth client secret
- Database password
- Environment-specific configuration

must never be committed to GitHub.

The repository `.gitignore` excludes local configuration and environment files.

---

## ▶️ Running Locally

### Prerequisites

Install:

- Java 25
- PostgreSQL
- Node.js and npm
- Ollama
- Git

---

### 1. Clone the Repository

```bash
git clone https://github.com/hgshreyas/SmartMail.git
cd SmartMail
```

---

### 2. Create PostgreSQL Database

```sql
CREATE DATABASE smartmail;
```

Configure database credentials locally.

---

### 3. Install Ollama Model

```bash
ollama pull llama3.2:1b
```

Make sure Ollama is running before starting AI review.

---

### 4. Configure Google OAuth

Create a Google Cloud OAuth client and enable the Gmail API.

SmartMail requires Gmail modification permission.

Store OAuth credentials only in local configuration or environment variables.

Do not commit credentials to Git.

---

### 5. Start Backend

```bash
cd backend
mvn spring-boot:run
```

Backend:

```text
http://localhost:8080
```

---

### 6. Authenticate with Google

Open:

```text
http://localhost:8080/oauth2/authorization/google
```

Complete Google authorization.

---

### 7. Start Frontend

Open another terminal:

```bash
cd frontend
npm install
npm run dev
```

Frontend:

```text
http://localhost:5173
```

---

## 🧪 Testing

SmartMail has been tested against a large real Gmail inbox containing thousands of messages.

Testing covered:

- OAuth2 authentication
- Gmail refresh tokens
- Full Gmail inbox pagination
- Large sequential Gmail batches
- Gmail quota handling
- Rule-based classification
- Pending-review queues
- Sequential Ollama classification
- Automatic Gmail Trash operations
- PostgreSQL persistence
- Duplicate prevention
- Manual Keep and Trash actions
- React dashboard
- Search and filters
- HTML email rendering
- Progress monitoring

Large Gmail runs completed successfully without requiring every email to be sent to the local AI model.

---

## 🛡️ Reliability Features

SmartMail includes several protections for large workloads:

- Rules before AI
- One Ollama worker at a time
- Gmail request pacing
- Gmail quota backoff
- Refreshable OAuth credentials
- Gmail pagination
- Sequential batch processing
- Persistent PostgreSQL state
- Gmail message-ID duplicate prevention
- AI-review tracking
- In-flight AI protection
- Human review for unresolved emails

---

## 🎯 Design Goals

SmartMail was designed around four main goals:

### Safety

Uncertain messages are not automatically modified.

### Efficiency

Deterministic rules handle obvious emails before AI is used.

### Reliability

Processing state is stored persistently and long Gmail operations support retry and token refresh.

### Local AI

Email classification can use a locally running LLM instead of requiring a hosted AI API.

---

## 🚀 Future Improvements

Possible future enhancements include:

- User-configurable classification rules
- Multiple Gmail account support
- Scheduled automatic inbox scans
- Improved AI confidence calibration
- Attachment analysis
- Additional Gmail actions such as Archive and Labels
- Analytics and email-category visualizations
- Docker support
- Cloud deployment
- Hosted AI option for environments without local Ollama
- Automated integration testing
- Improved production monitoring

---

## 🧠 Engineering Concepts Demonstrated

This project demonstrates practical use of:

- Spring Boot REST APIs
- Spring Security
- OAuth2
- Google Gmail API
- PostgreSQL
- Spring Data JPA
- React
- Vite
- Local LLM integration
- Prompt engineering
- Structured AI output
- Background processing
- API pagination
- Rate-limit handling
- Retry and backoff strategies
- OAuth refresh tokens
- Concurrency control
- Persistent state management
- Human-in-the-loop AI
- Git and GitHub

---

## 📌 Project Summary

SmartMail is a full-stack AI-powered Gmail-management application combining:

```text
Gmail
+
OAuth2
+
Spring Boot
+
PostgreSQL
+
Deterministic Rules
+
Local AI
+
React
```

The project demonstrates how traditional software rules and AI can work together to automate email management while retaining a human-review safety layer.

---

## 👤 Author

**H G Shreyas**

---

## 📄 License

This project is intended for educational, portfolio, experimentation, and personal-use purposes.
