# SmartMail 📧🤖

SmartMail is an AI-powered email management system that connects to Gmail, analyzes incoming emails, classifies them based on their actual content, and helps users automatically organize unwanted emails.

The system uses **Spring Boot** for the backend, **React + Vite** for the frontend, **PostgreSQL** for persistence, **Google Gmail API** for email operations, and **Ollama (Llama 3.2)** for local AI-based email classification.

---

## 🚀 Features

### 🔐 Google OAuth2 Authentication

- Secure Google login using OAuth2.
- Accesses Gmail through Google's authorized APIs.
- Uses Gmail Modify permission for email actions.
- Does not require storing the user's Gmail password.

### 📬 Gmail Integration

SmartMail connects directly to the user's Gmail account using the Gmail API.

The system can:

- Fetch emails from the Gmail inbox.
- Read email metadata.
- Extract sender information.
- Extract email subjects.
- Extract email bodies.
- Read Gmail message IDs.
- Read Gmail labels and other useful metadata.
- Modify Gmail labels.
- Move emails to Gmail Trash.

### 🤖 AI Email Classification

SmartMail classifies emails into exactly three categories:

| Category | Meaning |
|----------|---------|
| IMPORTANT | Personal, transactional, security, academic, job, or work-related emails |
| PROMOTIONAL | Marketing, advertisements, offers, newsletters, surveys, and promotional content |
| SPAM | Suspicious, fraudulent, phishing, malicious, or deceptive emails |

The classification is based primarily on the **actual purpose and content of the email**.

SmartMail does not rely only on:

- Sender name
- Sender domain
- Company name
- Keyword matching

Instead, the AI analyzes the overall context and purpose of the email.

### 🎯 Confidence-Based Decisions

The AI returns a confidence score along with the classification.

SmartMail uses predefined confidence thresholds before automatically performing Gmail actions.

| Category | Confidence | Action |
|----------|------------|--------|
| IMPORTANT | ≥ 80% | KEEP |
| PROMOTIONAL | ≥ 90% | TRASH |
| SPAM | ≥ 90% | TRASH |
| Any category below threshold | Any | PENDING_REVIEW |

This prevents uncertain AI predictions from automatically modifying emails.

### 🗑️ Automatic Gmail Actions

SmartMail can automatically perform Gmail actions based on the classification result.

- High-confidence IMPORTANT emails are kept.
- High-confidence PROMOTIONAL emails are moved to Gmail Trash.
- High-confidence SPAM emails are moved to Gmail Trash.
- Low-confidence emails are marked as `PENDING_REVIEW`.
- Users can manually Keep or Trash emails requiring review.

### 🔎 Email Search and Filtering

The frontend provides:

- Search by sender.
- Search by subject.
- Search by email content.
- Filter by category.
- Filter by action.
- Email counts.
- Email details.
- Email body viewing.
- HTML email rendering.

### 👤 Human Review

When SmartMail is not sufficiently confident about an email, it does not automatically perform the action.

Instead, the email is marked:

`PENDING_REVIEW`

The user can then manually choose:

- ✓ Keep
- 🗑 Trash

This provides a safety layer between AI predictions and permanent email management.

---

## 🏗️ System Architecture

```text
                    ┌──────────────────────┐
                    │      Gmail API       │
                    └──────────┬───────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────┐
│              Spring Boot Backend                │
│                                                 │
│  Google OAuth2                                  │
│       │                                         │
│       ▼                                         │
│  Gmail Service                                  │
│       │                                         │
│       ▼                                         │
│  Email Classifier Service                       │
│       │                                         │
│       ▼                                         │
│  Ollama / Llama 3.2                             │
│       │                                         │
│       ▼                                         │
│  IMPORTANT / PROMOTIONAL / SPAM                │
│       │                                         │
│       ▼                                         │
│  PostgreSQL Database                            │
└──────────────────────┬──────────────────────────┘
                       │
                       ▼
              ┌─────────────────┐
              │ React + Vite UI │
              └─────────────────┘
```                       

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| **Programming Language** | Java 25 |
| **Backend Framework** | Spring Boot 4.1.1 |
| **Security & Authentication** | Spring Security, Spring OAuth2 Client |
| **API Integration** | Google Gmail API |
| **AI / LLM** | Ollama, Llama 3.2 3B |
| **Database** | PostgreSQL |
| **Persistence** | Spring Data JPA |
| **Frontend** | React |
| **Frontend Tooling** | Vite |
| **Build Tool** | Maven |
| **Web Technologies** | HTML, CSS, JavaScript |

---


## 📁 Project Structure

```text
SmartMail/
│
├── backend/
│   ├── src/
│   │   └── main/
│   │       ├── java/
│   │       │   └── com/
│   │       │       └── smartmail/
│   │       │           └── backend/
│   │       │               ├── controller/
│   │       │               ├── model/
│   │       │               ├── repository/
│   │       │               └── service/
│   │       │
│   │       └── resources/
│   │           └── application.properties
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
## 🔄 Application Workflow

```text
User
 │
 ▼
Google OAuth2 Login
 │
 ▼
Gmail Authorization
 │
 ▼
Gmail API
 │
 ▼
Fetch Inbox Emails
 │
 ▼
Extract Email Content
 │
 ▼
AI Email Classification
 │
 ▼
Ollama / Llama 3.2
 │
 ▼
Category + Confidence
 │
 ├── IMPORTANT ───────► KEEP
 │
 ├── PROMOTIONAL ─────► TRASH
 │
 ├── SPAM ─────────────► TRASH
 │
 └── Low Confidence ──► PENDING_REVIEW
                              │
                              ▼
                         User Decision
                         ├── KEEP
                         └── TRASH
                              │
                              ▼
                         PostgreSQL
                              │
                              ▼
                       React Dashboard
```
---
## 🧠 AI Classification

SmartMail uses **Ollama with Llama 3.2 3B** to analyze the content of incoming emails and determine their purpose.

The AI analyzes:

- Sender
- Subject
- Email body
- Overall context
- Purpose of the message
- Whether the email is useful, promotional, or suspicious

The model returns a structured result containing:

- **Category**
- **Confidence score**
- **Reason**

SmartMail then uses its confidence-based decision logic to determine the final action, such as `KEEP`, `TRASH`, or `PENDING_REVIEW`.

---

## 📊 Classification Categories

### ⭐ IMPORTANT

Includes emails that are potentially useful or important to the user.

Examples:

- Banking and UPI transactions
- Security alerts
- Job and recruitment emails
- Hackathons
- Registration deadlines
- Academic or work-related emails
- Important personal communication

**Action:** `KEEP`

---

### 📢 PROMOTIONAL

Includes legitimate marketing and promotional communication.

Examples:

- Advertisements
- Marketing emails
- Offers and discounts
- Newsletters
- Surveys
- Promotional campaigns

**Action:** `TRASH` when confidence is at least `90%`.

---

### 🚨 SPAM

Includes suspicious or potentially harmful emails.

Examples:

- Phishing attempts
- Fraudulent messages
- Fake prizes or lottery messages
- Malicious or deceptive emails
- Suspicious unsolicited messages

**Action:** `TRASH` when confidence is at least `90%`.

---

## 🎯 Confidence System

SmartMail does not automatically trust every AI prediction.

The classification result is evaluated against predefined confidence thresholds before any automatic Gmail action is performed.

| Category | Confidence Threshold | Result |
|---|---:|---|
| **IMPORTANT** | ≥ 0.80 | `KEEP` |
| **PROMOTIONAL** | ≥ 0.90 | `TRASH` |
| **SPAM** | ≥ 0.90 | `TRASH` |
| **Any category below threshold** | Below required threshold | `PENDING_REVIEW` |

### Decision Flow

```text
IMPORTANT
   │
   ├── Confidence >= 0.80 ──► KEEP
   │
   └── Confidence < 0.80 ───► PENDING_REVIEW


PROMOTIONAL
   │
   ├── Confidence >= 0.90 ──► TRASH
   │
   └── Confidence < 0.90 ───► PENDING_REVIEW


SPAM
   │
   ├── Confidence >= 0.90 ──► TRASH
   │
   └── Confidence < 0.90 ───► PENDING_REVIEW
```
## 📬 Gmail Integration

SmartMail integrates with Gmail through the **Google Gmail API**.

The application uses the authorized Gmail account to retrieve and manage emails without requiring the user's Gmail password.

### Gmail Operations

SmartMail can:

- Fetch emails from the Gmail inbox.
- Retrieve Gmail message IDs.
- Extract sender information.
- Extract email subjects.
- Extract email bodies.
- Read Gmail labels and message metadata.
- Modify Gmail messages.
- Move emails to Gmail Trash.

### Gmail Processing Flow

```text
Gmail Inbox
     │
     ▼
Gmail API
     │
     ▼
Fetch Message
     │
     ▼
Extract Email Content
     │
     ├── Sender
     ├── Subject
     └── Body
     │
     ▼
AI Classification
     │
     ▼
Category + Confidence
     │
     ├── IMPORTANT ───────► KEEP
     │
     ├── PROMOTIONAL ─────► TRASH
     │
     ├── SPAM ─────────────► TRASH
     │
     └── Low Confidence ──► PENDING_REVIEW
```
## 🗑️ Automatic Gmail Actions

SmartMail performs Gmail actions based on the AI classification and confidence score.

| Classification | Confidence | Gmail Action |
|---|---:|---|
| **IMPORTANT** | ≥ 0.80 | `KEEP` |
| **PROMOTIONAL** | ≥ 0.90 | `TRASH` |
| **SPAM** | ≥ 0.90 | `TRASH` |
| **Below threshold** | Any | `PENDING_REVIEW` |

### Automatic Processing

```text
AI Classification
       │
       ▼
Confidence Check
       │
       ├── IMPORTANT + High Confidence
       │          └──► KEEP
       │
       ├── PROMOTIONAL + High Confidence
       │          └──► TRASH
       │
       ├── SPAM + High Confidence
       │          └──► TRASH
       │
       └── Below Threshold
                  └──► PENDING_REVIEW
```
## 👤 Human Review

SmartMail does not automatically modify Gmail when the AI is not sufficiently confident in its classification.

Instead, the email is marked as:

`PENDING_REVIEW`

These emails are presented to the user for manual decision-making.

### Available User Actions

| Action | Result |
|---|---|
| ✓ **Keep** | Email remains in the inbox |
| 🗑 **Trash** | Email is moved to Gmail Trash |

This human-review step provides an additional safety layer and prevents uncertain AI predictions from automatically affecting the user's emails.

---

## 🗃️ Database & Data Model

SmartMail uses **PostgreSQL** to persist processed email information and maintain the state of each email.

The database stores information such as:

- Gmail message ID
- Sender
- Subject
- Email body
- Classification
- Confidence score
- Recommended action
- AI review status

### Email Processing State

```text
Gmail Email
     │
     ▼
Fetch & Store
     │
     ▼
AI Classification
     │
     ▼
Classification + Confidence
     │
     ├── IMPORTANT ───────► KEEP
     │
     ├── PROMOTIONAL ─────► TRASH
     │
     ├── SPAM ─────────────► TRASH
     │
     └── Low Confidence ──► PENDING_REVIEW
```

## 🔄 Duplicate Prevention & AI Review Tracking

SmartMail prevents the same email from being unnecessarily processed by the AI or modified repeatedly.

Each processed email maintains an AI review status.

### AI Review Tracking

- Emails successfully classified by the AI are marked as `aiReviewed = true`.
- Emails that have already been reviewed are not sent to Ollama again.
- Emails that fail during AI processing remain available for review.
- Failed processing attempts can be retried later.

### Concurrent Processing Protection

SmartMail also prevents duplicate AI requests when the same Gmail message is encountered concurrently.

```text
Gmail Message
      │
      ▼
Already AI Reviewed?
      │
   ┌──┴──┐
  YES    NO
   │      │
   ▼      ▼
 Skip   Check In-Flight
          │
       ┌──┴──┐
      YES    NO
       │      │
       ▼      ▼
      Skip   Ollama
               │
               ▼
        Classification
               │
               ▼
        aiReviewed = true
```
## 🖥️ Frontend Dashboard

SmartMail provides a React-based dashboard for viewing and managing classified emails.

The dashboard allows users to:

- View processed emails.
- Search emails by sender, subject, or content.
- Filter emails by classification.
- Filter emails by action.
- View email counts.
- Open individual email details.
- View email bodies.
- Render HTML email content.
- Manually Keep emails requiring review.
- Manually Trash emails requiring review.

### Dashboard Workflow

```text
PostgreSQL
     │
     ▼
Spring Boot REST API
     │
     ▼
React + Vite Dashboard
     │
     ├── Search
     ├── Filter
     ├── Email Counts
     ├── Email Details
     ├── HTML Email View
     └── Human Review Actions
```
## 🔐 Security & Configuration

SmartMail uses Google OAuth2 to securely access the user's Gmail account.

### Security

- Uses Google OAuth2 authentication.
- Does not store Gmail passwords.
- Uses Gmail API access tokens for authorized operations.
- Requests Gmail permissions through Google's consent screen.
- Uses Gmail Modify permission for email management actions.

### Sensitive Configuration

Sensitive configuration should be kept outside the source repository.

This includes:

- Google OAuth client credentials
- Database passwords
- API credentials
- Other environment-specific secrets

These files and credentials should be excluded from Git using `.gitignore`.

> **Important:** Never commit OAuth secrets, database passwords, API keys, or other sensitive credentials to GitHub.

---
## ▶️ Running Locally

### Prerequisites

Make sure the following are installed:

- Java 25
- Maven
- PostgreSQL
- Ollama
- Node.js and npm

### 1. Clone the Repository

```bash
git clone https://github.com/hgshreyas/SmartMail
cd SmartMail
```
### 2. Start PostgreSQL

```sql
CREATE DATABASE smartmail;
```
### 3. Start Ollama

Make sure Ollama is running locally and the required model is available.

```bash
ollama pull llama3.2:3b
ollama serve
```
### 4. Configure the Backend
Configure the required PostgreSQL, Google OAuth2, and Ollama settings in the backend configuration.

Sensitive values should be stored in a local configuration file and should not be committed to Git.
### 5. Start the Backend
```bash
cd backend
mvn spring-boot:run
```
The backend runs on:

```text
http://localhost:8080
```
 ### 6. Start the Frontend
Open a new terminal:
```bash
cd frontend
npm install
npm run dev
```
The frontend runs on:

```text
http://localhost:5173
```
### 7. Login with Google
Open the application and authenticate using Google OAuth2.

```text
http://localhost:8080/oauth2/authorization/google
```
After authorization, SmartMail can access the user's Gmail account through the Gmail API.

## 🧪 Testing

SmartMail was tested using real Gmail emails covering different types of messages and classification scenarios.

### Classification Testing

The system was tested with emails such as:

| Email Type | Expected Category | Expected Action |
|---|---|---|
| Banking / UPI transaction | IMPORTANT | KEEP |
| Security alert | IMPORTANT | KEEP |
| Job / recruitment email | IMPORTANT | KEEP |
| Hackathon announcement | IMPORTANT | KEEP |
| Marketing email | PROMOTIONAL | TRASH |
| Advertisement | PROMOTIONAL | TRASH |
| Survey email | PROMOTIONAL | TRASH |
| Suspicious / fraudulent email | SPAM | TRASH |
| Phishing email | SPAM | TRASH |
| Low-confidence classification | Any | PENDING_REVIEW |

### Functional Testing

The following functionality has been tested:

- Google OAuth2 login.
- Gmail API authorization.
- Gmail inbox email fetching.
- Sender, subject, and body extraction.
- AI-based email classification.
- Confidence-based decision making.
- Automatic Gmail Trash actions.
- Human review for uncertain emails.
- Manual Keep action.
- Manual Trash action.
- PostgreSQL persistence.
- Duplicate AI processing prevention.
- React dashboard.
- Email search and filtering.
- Email details and HTML rendering.

### AI Testing

SmartMail has been tested with multiple real Gmail messages to verify that classification is based on the overall content and purpose of the email rather than relying only on sender names or keywords.

---
## 🧩 Design & Reliability

SmartMail is designed with a focus on safe AI-based email management and reliable processing.

### Safety-First AI Decisions

- AI predictions are combined with confidence thresholds.
- High-confidence classifications can trigger automatic Gmail actions.
- Low-confidence classifications are sent for human review.
- The system avoids making automatic decisions when the AI is uncertain.

### Reliable Email Processing

- Gmail message IDs are used to track individual emails.
- AI review status prevents unnecessary reprocessing.
- In-flight processing protection helps prevent duplicate AI requests.
- Gmail actions are performed only when the final decision requires an action.
- Failed AI processing can remain pending for later retry.

### Separation of Responsibilities

The application separates major responsibilities across different components:

```text
Controller
    │
    ▼
Service Layer
    │
    ├── Gmail Service
    │
    ├── AI Classification Service
    │
    └── Email Processing Logic
    │
    ▼
Repository
    │
    ▼
PostgreSQL
```
## 🚀 Future Improvements

The current version of SmartMail provides the core email classification and automation workflow. The following improvements can further enhance the system:

- **Gmail API Pagination**  
  Process more than the current batch of fetched emails by implementing proper Gmail API pagination.

- **Ollama Performance Optimization**  
  Optimize model execution, request batching, prompt size, and resource usage for faster and more efficient classification.

- **AI Processing Optimization**  
  Further tune concurrent AI processing to improve throughput while controlling CPU, memory, and system resource usage.

- **Background Processing & Progress Tracking**  
  Move large email-processing workloads into background jobs and provide users with processing progress and status updates.

- **Confidence Calibration**  
  Improve confidence-score calibration and decision thresholds using a larger set of real-world email examples.

- **Improved Email Content Extraction**  
  Enhance handling of complex HTML emails, attachments, and different MIME structures.

- **Advanced Email Rules**  
  Allow users to create custom rules and preferences for specific senders, domains, or email categories.

- **Analytics Dashboard**  
  Add detailed statistics and visualizations for email categories, spam detection, promotional activity, and automation results.

- **Production Deployment**  
  Deploy the complete application using Docker and a cloud infrastructure with production-grade configuration and monitoring.
---
## 🧠 Engineering Concepts & Learning Outcomes

Building SmartMail involved practical implementation of several software engineering concepts:

- **Spring Boot** — building REST APIs and structuring backend services.
- **Spring Data JPA** — database interaction and persistence.
- **PostgreSQL** — storing email data and processing state.
- **Spring Security & OAuth2** — implementing secure Google authentication.
- **Gmail API** — reading and modifying real Gmail messages.
- **REST API Integration** — connecting the backend with external services.
- **AI Integration** — using Ollama and a local LLM for email classification.
- **Prompt Engineering** — designing structured prompts for consistent AI responses.
- **Confidence-Based Decision Making** — controlling automated actions based on AI confidence.
- **State Tracking** — preventing unnecessary reprocessing of already reviewed emails.
- **Exception Handling** — handling failures during AI and Gmail operations.
- **React + Vite** — building the frontend dashboard.
- **Frontend–Backend Integration** — connecting the React application with Spring Boot APIs.
- **Git & GitHub** — version control and project management.
- **Layered Architecture** — separating controllers, services, repositories, and external integrations.

---
## 📌 Project Summary

SmartMail is a full-stack AI-powered email management system that combines Gmail integration, local AI classification, automated email actions, and human review.

The project demonstrates how modern backend technologies, external APIs, AI services, database persistence, authentication, and frontend development can be integrated into a single practical application.

---
## 👤 Author

**H G Shreyas**

SmartMail was developed as a hands-on full-stack project to explore AI integration, backend development, API integration, authentication, database persistence, and modern frontend development.

---
## 📄 License

This project is intended for educational and portfolio purposes.

You may modify and extend the project for learning, experimentation, and personal use.

---
## ⭐ Conclusion

SmartMail demonstrates the practical integration of **AI, backend development, external APIs, authentication, databases, and frontend technologies** to solve a real-world problem.

The project provides a foundation that can be further extended into a more scalable and intelligent email automation platform.

---
