import { useEffect, useMemo, useState } from "react";
import "./App.css";

function App() {
  const [emails, setEmails] = useState([]);
  const [filter, setFilter] = useState("ALL");
  const [search, setSearch] = useState("");
  const [selectedEmail, setSelectedEmail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [actionLoading, setActionLoading] = useState(false);

  const fetchEmails = async () => {
    try {
      const response = await fetch(
          "http://localhost:8080/emails/gmail/results"
      );

      if (!response.ok) {
        throw new Error("Could not fetch emails from SmartMail backend.");
      }

      const data = await response.json();

      setEmails(data);
      setError("");
    } catch (err) {
      console.error(err);

      setError(
          "Backend is not reachable. Make sure the SmartMail Spring Boot server is running."
      );
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchEmails();

    const interval = setInterval(fetchEmails, 3000);

    return () => clearInterval(interval);
  }, []);

  /*
   * KEEP EMAIL
   *
   * Sends the selected email ID to the Spring Boot backend.
   */
  const handleKeep = async () => {
    if (!selectedEmail || !selectedEmail.id) {
      return;
    }

    try {
      setActionLoading(true);
      setError("");

      const response = await fetch(
          `http://localhost:8080/emails/${selectedEmail.id}/keep`,
          {
            method: "POST",
            credentials: "include",
          }
      );

      if (!response.ok) {
        throw new Error("Could not keep the email.");
      }

      const updatedEmail = await response.json();

      setEmails((currentEmails) =>
          currentEmails.map((email) =>
              email.id === updatedEmail.id
                  ? updatedEmail
                  : email
          )
      );

      setSelectedEmail(null);

    } catch (err) {
      console.error(err);

      setError(
          "Could not keep this email. Make sure the SmartMail backend is running."
      );

    } finally {
      setActionLoading(false);
    }
  };

  /*
   * MOVE EMAIL TO TRASH
   *
   * Sends the selected email ID to the Spring Boot backend.
   */
  const handleTrash = async () => {
    if (!selectedEmail || !selectedEmail.id) {
      return;
    }

    try {
      setActionLoading(true);
      setError("");

      const response = await fetch(
          `http://localhost:8080/emails/${selectedEmail.id}/trash`,
          {
            method: "POST",
            credentials: "include",
          }
      );

      if (!response.ok) {
        throw new Error("Could not move the email to trash.");
      }

      const updatedEmail = await response.json();

      setEmails((currentEmails) =>
          currentEmails.map((email) =>
              email.id === updatedEmail.id
                  ? updatedEmail
                  : email
          )
      );

      setSelectedEmail(null);

    } catch (err) {
      console.error(err);

      setError(
          "Could not move this email to trash. Make sure the SmartMail backend is running."
      );

    } finally {
      setActionLoading(false);
    }
  };

  const counts = useMemo(() => {
    return {
      all: emails.length,

      important: emails.filter(
          (email) => email.category === "IMPORTANT"
      ).length,

      promotional: emails.filter(
          (email) => email.category === "PROMOTIONAL"
      ).length,

      spam: emails.filter(
          (email) => email.category === "SPAM"
      ).length,

      review: emails.filter(
          (email) => email.action === "PENDING_REVIEW"
      ).length,
    };
  }, [emails]);

  const filteredEmails = useMemo(() => {
    return emails.filter((email) => {
      const matchesFilter =
          filter === "ALL"
              ? true
              : filter === "REVIEW"
                  ? email.action === "PENDING_REVIEW"
                  : email.category === filter;

      const searchText = search.toLowerCase();

      const matchesSearch =
          !searchText ||
          (email.sender || "").toLowerCase().includes(searchText) ||
          (email.subject || "").toLowerCase().includes(searchText) ||
          (email.body || "").toLowerCase().includes(searchText);

      return matchesFilter && matchesSearch;
    });
  }, [emails, filter, search]);

  const getCategoryClass = (category) => {
    switch (category) {
      case "IMPORTANT":
        return "badge important";

      case "PROMOTIONAL":
        return "badge promotional";

      case "SPAM":
        return "badge spam";

      default:
        return "badge other";
    }
  };

  const getActionClass = (action) => {
    switch (action) {
      case "KEEP":
        return "action keep";

      case "TRASH":
        return "action trash";

      case "PENDING_REVIEW":
        return "action review";

      default:
        return "action";
    }
  };

  const formatConfidence = (confidence) => {
    if (confidence === null || confidence === undefined) {
      return "-";
    }

    return `${Math.round(confidence * 100)}%`;
  };

  /*
   * Remove the HTML tags from an email body.
   *
   * Gmail emails are often stored as HTML.
   * We use this function for the short preview in
   * the email list so that the dashboard doesn't
   * show things like <html>, <body>, <table>, etc.
   */
  const getTextPreview = (body, length = 180) => {
    if (!body) {
      return "No message body available.";
    }

    const temp = document.createElement("div");

    temp.innerHTML = body;

    const text = (temp.textContent || temp.innerText || "")
        .replace(/\s+/g, " ")
        .trim();

    if (!text) {
      return "No readable message content.";
    }

    return text.length > length
        ? `${text.substring(0, length)}...`
        : text;
  };

  /*
   * Check whether the email body contains HTML.
   */
  const isHtmlEmail = (body) => {
    if (!body) {
      return false;
    }

    return /<\s*(html|body|table|div|p|br|img|a|style)[^>]*>/i.test(
        body
    );
  };

  return (
      <div className="app">

        {/* =========================================================
          TOP BAR
      ========================================================== */}

        <header className="topbar">

          <div className="brand">

            <div className="brand-icon">
              ✉
            </div>

            <div>
              <h1>SmartMail</h1>

              <p>
                AI-Powered Gmail Assistant
              </p>
            </div>

          </div>

          <div className="connection">

            <span className="connection-dot"></span>

            Gmail Connected

          </div>

        </header>


        {/* =========================================================
          MAIN DASHBOARD
      ========================================================== */}

        <main className="dashboard">

          <section className="welcome">

            <div>

              <h2>
                Inbox Overview
              </h2>

              <p>
                SmartMail automatically analyzes your emails and recommends
                actions using AI.
              </p>

            </div>

            <button
                className="refresh-button"
                onClick={fetchEmails}
            >
              ↻ Refresh
            </button>

          </section>


          {/* =====================================================
            ERROR
        ====================================================== */}

          {error && (
              <div className="error-message">
                {error}
              </div>
          )}


          {/* =====================================================
            STATISTICS
        ====================================================== */}

          <section className="stats">

            <button
                className={`stat-card ${
                    filter === "ALL"
                        ? "selected-stat"
                        : ""
                }`}
                onClick={() => setFilter("ALL")}
            >

              <div className="stat-icon all-icon">
                ✉
              </div>

              <div>

                <span>
                  Total Emails
                </span>

                <strong>
                  {counts.all}
                </strong>

              </div>

            </button>


            <button
                className={`stat-card ${
                    filter === "IMPORTANT"
                        ? "selected-stat"
                        : ""
                }`}
                onClick={() => setFilter("IMPORTANT")}
            >

              <div className="stat-icon important-icon">
                ★
              </div>

              <div>

                <span>
                  Important
                </span>

                <strong>
                  {counts.important}
                </strong>

              </div>

            </button>


            <button
                className={`stat-card ${
                    filter === "PROMOTIONAL"
                        ? "selected-stat"
                        : ""
                }`}
                onClick={() => setFilter("PROMOTIONAL")}
            >

              <div className="stat-icon promotional-icon">
                %
              </div>

              <div>

                <span>
                  Promotional
                </span>

                <strong>
                  {counts.promotional}
                </strong>

              </div>

            </button>


            <button
                className={`stat-card ${
                    filter === "SPAM"
                        ? "selected-stat"
                        : ""
                }`}
                onClick={() => setFilter("SPAM")}
            >

              <div className="stat-icon spam-icon">
                !
              </div>

              <div>

                <span>
                  Spam
                </span>

                <strong>
                  {counts.spam}
                </strong>

              </div>

            </button>


            <button
                className={`stat-card ${
                    filter === "REVIEW"
                        ? "selected-stat"
                        : ""
                }`}
                onClick={() => setFilter("REVIEW")}
            >

              <div className="stat-icon review-icon">
                ?
              </div>

              <div>

                <span>
                  Review
                </span>

                <strong>
                  {counts.review}
                </strong>

              </div>

            </button>

          </section>


          {/* =====================================================
            EMAIL SECTION
        ====================================================== */}

          <section className="email-section">

            <div className="section-header">

              <div>

                <h2>
                  Email Intelligence
                </h2>

                <p>
                  Showing {filteredEmails.length} of {emails.length} emails
                </p>

              </div>


              <div className="search-box">

                <span>
                  ⌕
                </span>

                <input
                    type="text"
                    placeholder="Search sender, subject or content..."
                    value={search}
                    onChange={(event) =>
                        setSearch(event.target.value)
                    }
                />

              </div>

            </div>


            {/* ===================================================
              FILTER BUTTONS
          ==================================================== */}

            <div className="filters">

              <button
                  className={
                    filter === "ALL"
                        ? "active-filter"
                        : ""
                  }
                  onClick={() => setFilter("ALL")}
              >
                All
              </button>


              <button
                  className={
                    filter === "IMPORTANT"
                        ? "active-filter"
                        : ""
                  }
                  onClick={() => setFilter("IMPORTANT")}
              >
                Important
              </button>


              <button
                  className={
                    filter === "PROMOTIONAL"
                        ? "active-filter"
                        : ""
                  }
                  onClick={() => setFilter("PROMOTIONAL")}
              >
                Promotional
              </button>


              <button
                  className={
                    filter === "SPAM"
                        ? "active-filter"
                        : ""
                  }
                  onClick={() => setFilter("SPAM")}
              >
                Spam
              </button>


              <button
                  className={
                    filter === "REVIEW"
                        ? "active-filter"
                        : ""
                  }
                  onClick={() => setFilter("REVIEW")}
              >
                Pending Review
              </button>

            </div>


            {/* ===================================================
              LOADING
          ==================================================== */}

            {loading ? (

                <div className="empty-state">

                  <div className="loader"></div>

                  <p>
                    Loading SmartMail emails...
                  </p>

                </div>


            ) : filteredEmails.length === 0 ? (

                <div className="empty-state">

                  <div className="empty-icon">
                    ✉
                  </div>

                  <h3>
                    No emails found
                  </h3>

                  <p>

                    {emails.length === 0
                        ? "Run the Gmail test from the backend first."
                        : "Try changing the filter or search term."}

                  </p>

                </div>


            ) : (

                <div className="email-list">

                  {filteredEmails.map((email) => (

                      <article
                          className="email-card"
                          key={
                              email.id ||
                              email.gmailMessageId
                          }
                          onClick={() =>
                              setSelectedEmail(email)
                          }
                      >

                        <div className="email-avatar">

                          {(email.sender || "?")
                              .charAt(0)
                              .toUpperCase()}

                        </div>


                        <div className="email-main">

                          <div className="email-top">

                            <div className="sender">
                              {email.sender ||
                                  "Unknown sender"}
                            </div>


                            <div className="email-badges">

                              <span
                                  className={getCategoryClass(
                                      email.category
                                  )}
                              >
                                {email.category ||
                                    "UNCLASSIFIED"}
                              </span>


                              <span
                                  className={getActionClass(
                                      email.action
                                  )}
                              >
                                {email.action ||
                                    "PENDING"}
                              </span>

                            </div>

                          </div>


                          <h3>
                            {email.subject ||
                                "(No subject)"}
                          </h3>


                          {/* =================================================
                        CLEAN EMAIL PREVIEW
                    ================================================== */}

                          <p className="preview">

                            {getTextPreview(
                                email.body
                            )}

                          </p>


                          <div className="email-meta">

                            <span>

                              AI Confidence:{" "}

                              <strong>
                                {formatConfidence(
                                    email.confidence
                                )}
                              </strong>

                            </span>


                            {email.aiReviewed && (

                                <span className="ai-reviewed">
                                  ✓ AI Reviewed
                                </span>

                            )}


                            {email.action ===
                                "PENDING_REVIEW" && (

                                    <span className="review-needed">
                                      Needs Review
                                    </span>

                                )}

                          </div>

                        </div>

                      </article>

                  ))}

                </div>

            )}

          </section>

        </main>


        {/* =========================================================
          EMAIL DETAIL MODAL
      ========================================================== */}

        {selectedEmail && (

            <div
                className="modal-overlay"
                onClick={() =>
                    !actionLoading && setSelectedEmail(null)
                }
            >

              <div
                  className="email-modal"
                  onClick={(event) =>
                      event.stopPropagation()
                  }
              >

                <button
                    className="close-button"
                    onClick={() =>
                        !actionLoading && setSelectedEmail(null)
                    }
                    disabled={actionLoading}
                >
                  ×
                </button>


                {/* ===================================================
                MODAL HEADER
            ==================================================== */}

                <div className="modal-header">

                  <div className="email-avatar large-avatar">

                    {(selectedEmail.sender || "?")
                        .charAt(0)
                        .toUpperCase()}

                  </div>


                  <div>

                    <h2>
                      {selectedEmail.subject ||
                          "(No subject)"}
                    </h2>

                    <p>
                      {selectedEmail.sender ||
                          "Unknown sender"}
                    </p>

                  </div>

                </div>


                {/* ===================================================
                MODAL CLASSIFICATION
            ==================================================== */}

                <div className="modal-badges">

                  <span
                      className={getCategoryClass(
                          selectedEmail.category
                      )}
                  >
                    {selectedEmail.category ||
                        "UNCLASSIFIED"}
                  </span>


                  <span
                      className={getActionClass(
                          selectedEmail.action
                      )}
                  >
                    {selectedEmail.action ||
                        "PENDING"}
                  </span>


                  <span className="confidence-badge">

                    Confidence:{" "}

                    {formatConfidence(
                        selectedEmail.confidence
                    )}

                  </span>

                </div>


                {/* ===================================================
                MODAL EMAIL BODY
            ==================================================== */}

                <div className="modal-body">

                  {isHtmlEmail(
                      selectedEmail.body
                  ) ? (

                      <iframe
                          className="modal-email-frame"
                          title="Email content"
                          sandbox="allow-same-origin"
                          srcDoc={selectedEmail.body}
                      />

                  ) : (

                      <div className="modal-plain-body">

                        {selectedEmail.body ||
                            "No message body available."}

                      </div>

                  )}

                </div>


                {/* ===================================================
                HUMAN REVIEW
            ==================================================== */}

                {selectedEmail.action ===
                    "PENDING_REVIEW" && (

                        <div className="review-panel">

                          <h3>
                            Human Review Required
                          </h3>


                          <p>
                            SmartMail is not confident enough
                            to automatically move this email.
                            You can review it before taking action.
                          </p>


                          <div className="review-buttons">

                            <button
                                className="keep-button"
                                onClick={handleKeep}
                                disabled={actionLoading}
                            >
                              {actionLoading
                                  ? "Processing..."
                                  : "✓ Keep"}
                            </button>


                            <button
                                className="trash-button"
                                onClick={handleTrash}
                                disabled={actionLoading}
                            >
                              {actionLoading
                                  ? "Processing..."
                                  : "🗑 Move to Trash"}
                            </button>

                          </div>

                        </div>

                    )}

              </div>

            </div>

        )}

      </div>
  );
}

export default App;