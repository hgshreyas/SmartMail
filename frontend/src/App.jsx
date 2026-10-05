import { useCallback, useEffect, useMemo, useState } from "react";
import "./App.css";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

function App() {
  const [emails, setEmails] = useState([]);
  const [filter, setFilter] = useState("ALL");
  const [search, setSearch] = useState("");
  const [selectedEmail, setSelectedEmail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [actionLoading, setActionLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [connected, setConnected] = useState(false);
  const [reviewProgress, setReviewProgress] = useState(null);
  const [gmailProgress, setGmailProgress] = useState(null);
  const [lastUpdated, setLastUpdated] = useState(null);
  const [user, setUser] = useState(null);
  const [authLoading, setAuthLoading] = useState(true);
  const [csrfToken, setCsrfToken] = useState("");

  const fetchEmails = useCallback(async () => {
    try {
      const response = await fetch(
          `${API_BASE}/emails/gmail/results`,
          {
            credentials: "include",
          }
      );

      if (response.status === 401) {
        setUser(null);
        setEmails([]);
        return;
      }
      if (!response.ok) {
        throw new Error("Could not fetch emails from SmartMail backend.");
      }

      const data = await response.json();

      setEmails(Array.isArray(data) ? data : []);
      setConnected(true);
      setLastUpdated(new Date());
      setError("");
    } catch (err) {
      console.error(err);

      setConnected(false);

      setError(
          "Backend is not reachable. Make sure the SmartMail Spring Boot server is running."
      );
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchReviewProgress = useCallback(async () => {
    try {
      const response = await fetch(
          `${API_BASE}/emails/pending-review/progress`,
          {
            credentials: "include",
          }
      );

      if (!response.ok) {
        return;
      }

      const data = await response.json();

      setReviewProgress(data);
    } catch (err) {
      console.debug("Pending-review progress is unavailable.", err);
    }
  }, []);

  const fetchGmailProgress = useCallback(async () => {
    try {
      const response = await fetch(`${API_BASE}/emails/gmail/progress`, { credentials: "include" });
      if (response.ok) setGmailProgress(await response.json());
    } catch (err) {
      console.debug("Gmail sync progress is unavailable.", err);
    }
  }, []);

  const handleProcessReview = async () => {
    try {
      setRefreshing(true);
      const response = await fetch(`${API_BASE}/emails/pending-review/process`, {
        method: "POST",
        credentials: "include",
        headers: { "X-XSRF-TOKEN": csrfToken },
      });
      if (!response.ok) throw new Error("Could not start AI review.");
      const result = await response.json();
      setReviewProgress(result.progress);
      setError("");
    } catch (err) {
      console.error(err);
      setError("Could not start the pending review processor.");
    } finally {
      setRefreshing(false);
    }
  };

  const handleRefresh = async () => {
    try {
      setRefreshing(true);

      await Promise.all([
        fetchEmails(),
        fetchReviewProgress(),
        fetchGmailProgress(),
      ]);
    } finally {
      setRefreshing(false);
    }
  };

  useEffect(() => {
    let active = true;
    const loadSession = async () => {
      try {
        const csrfResponse = await fetch(`${API_BASE}/api/csrf`, { credentials: "include" });
        if (csrfResponse.ok) {
          const csrfData = await csrfResponse.json();
          if (active) setCsrfToken(csrfData.token || "");
        }
        const response = await fetch(`${API_BASE}/api/me`, { credentials: "include" });
        if (response.ok) {
          const profile = await response.json();
          if (active) setUser(profile);
        } else if (active) {
          setUser(null);
        }
      } catch (err) {
        console.error("Could not check SmartMail sign-in.", err);
        if (active) setConnected(false);
      } finally {
        if (active) setAuthLoading(false);
      }
    };
    loadSession();
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (authLoading || !user) return undefined;
    const poll = () => {
      fetchEmails();
      fetchReviewProgress();
      fetchGmailProgress();
    };
    const initialPoll = setTimeout(poll, 0);
    const interval = setInterval(poll, 3000);
    return () => {
      clearTimeout(initialPoll);
      clearInterval(interval);
    };
  }, [authLoading, user, fetchEmails, fetchReviewProgress, fetchGmailProgress]);

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
          `${API_BASE}/emails/${selectedEmail.id}/keep`,
          {
            method: "POST",
            credentials: "include",
            headers: { "X-XSRF-TOKEN": csrfToken },
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
          `${API_BASE}/emails/${selectedEmail.id}/trash`,
          {
            method: "POST",
            credentials: "include",
            headers: { "X-XSRF-TOKEN": csrfToken },
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

  const handleSyncGmail = async () => {
    try {
      setRefreshing(true);
      const response = await fetch(`${API_BASE}/emails/gmail/sync`, {
        method: "POST",
        credentials: "include",
        headers: { "X-XSRF-TOKEN": csrfToken },
      });
      if (!response.ok) throw new Error("Could not start Gmail sync.");
      await fetchGmailProgress();
      setError("");
    } catch (err) {
      console.error(err);
      setError("Could not start Gmail sync. Please sign in again and retry.");
    } finally {
      setRefreshing(false);
    }
  };

  const handleSignOut = async () => {
    try {
      const response = await fetch(`${API_BASE}/logout`, {
        method: "POST",
        credentials: "include",
        headers: { "X-XSRF-TOKEN": csrfToken },
      });
      if (!response.ok) throw new Error("Sign out was rejected.");
      setUser(null);
      setEmails([]);
      setSelectedEmail(null);
    } catch (err) {
      console.error(err);
      setError("Could not sign out. Please refresh and try again.");
    }
  };

  const visibleEmails = useMemo(() => {
    return emails.filter((email) => {
      const gmailMessageId = String(email.gmailMessageId || "");

      return !gmailMessageId.startsWith("retry-test-");
    });
  }, [emails]);

  const counts = useMemo(() => {
    return {
      all: visibleEmails.length,

      important: visibleEmails.filter(
          (email) => email.category === "IMPORTANT"
      ).length,

      promotional: visibleEmails.filter(
          (email) => email.category === "PROMOTIONAL"
      ).length,

      spam: visibleEmails.filter(
          (email) => email.category === "SPAM"
      ).length,

      review: visibleEmails.filter(
          (email) =>
              email.action === "PENDING_REVIEW" &&
              email.aiReviewed === true
      ).length,

      aiQueue: visibleEmails.filter(
          (email) =>
              email.action === "PENDING_REVIEW" &&
              email.aiReviewed === false
      ).length,
    };
  }, [visibleEmails]);

  const filteredEmails = useMemo(() => {
    return visibleEmails.filter((email) => {
      const matchesFilter =
          filter === "ALL"
              ? true
              : filter === "REVIEW"
                  ? email.action === "PENDING_REVIEW" &&
                  email.aiReviewed === true
                  : email.category === filter;

      const searchText = search.toLowerCase().trim();

      const matchesSearch =
          !searchText ||
          (email.sender || "").toLowerCase().includes(searchText) ||
          (email.subject || "").toLowerCase().includes(searchText) ||
          (email.body || "").toLowerCase().includes(searchText);

      return matchesFilter && matchesSearch;
    });
  }, [visibleEmails, filter, search]);

  const formatStage = (stage) => {
    if (!stage) {
      return "Idle";
    }

    return stage
        .toLowerCase()
        .split("_")
        .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
        .join(" ");
  };

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

  if (authLoading) {
    return <div className="app"><main className="dashboard"><h2>Loading SmartMail...</h2></main></div>;
  }

  if (!user) {
    return (
      <div className="app">
        <header className="topbar">
          <div className="brand"><div className="brand-icon">✉</div><div><h1>SmartMail</h1><p>AI-Powered Gmail Assistant</p></div></div>
        </header>
        <main className="dashboard">
          <section className="welcome">
            <div><h2>Manage your inbox with confidence</h2><p>Sign in with Google to securely review and organize your Gmail.</p></div>
            <button className="refresh-button" onClick={() => window.location.assign(`${API_BASE}/oauth2/authorization/google`)}>Sign in with Google</button>
          </section>
          <p>Your emails remain tied to your Google account and are not shared with other SmartMail users.</p>
        </main>
      </div>
    );
  }

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

          <div className="account-controls">
            <span>{user.name || user.email}</span>
            <button className="refresh-button" onClick={handleSignOut}>Sign out</button>
          </div>

          <div
              className={`connection ${
                  connected ? "" : "connection-offline"
              }`}
          >

            <span className="connection-dot"></span>

            {connected ? "SmartMail Online" : "Backend Offline"}

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

            <div className="welcome-actions">
            <button
                className="refresh-button"
                onClick={handleSyncGmail}
                disabled={refreshing || gmailProgress?.processing}
            >
              {gmailProgress?.processing ? formatStage(gmailProgress.stage) : "↻ Sync Gmail"}
            </button>
            <button
                className="refresh-button"
                onClick={handleProcessReview}
                disabled={refreshing || reviewProgress?.processing}
            >
              {reviewProgress?.processing ? "Reviewing..." : "Run AI Review"}
            </button>
            <button
                className="refresh-button"
                onClick={handleRefresh}
                disabled={refreshing}
            >
              {refreshing ? "Refreshing..." : "↻ Refresh"}
            </button>
            </div>

          </section>


          <section className="system-status">

            <div
                className={`status-item ${
                    connected ? "status-ok" : "status-error"
                }`}
            >
              <span className="status-dot"></span>

              <div>
                <small>Backend</small>
                <strong>
                  {connected ? "Online" : "Offline"}
                </strong>
              </div>
            </div>

            <div className="status-item">
              <div>
                <small>AI Queue</small>
                <strong>{counts.aiQueue}</strong>
              </div>
            </div>

            <div className="status-item">
              <div>
                <small>Human Review</small>
                <strong>{counts.review}</strong>
              </div>
            </div>

            <div
                className={`status-item ${
                    reviewProgress?.processing
                        ? "status-working"
                        : ""
                }`}
            >
              <div>
                <small>Review Processor</small>

                <strong>
                  {reviewProgress?.processing
                      ? formatStage(reviewProgress.stage)
                      : "Idle"}
                </strong>
              </div>
            </div>

            <div className="status-item status-updated">
              <div>

                <small>Last Updated</small>

                <strong>
                  {lastUpdated
                      ? lastUpdated.toLocaleTimeString([], {
                        hour: "2-digit",
                        minute: "2-digit",
                        second: "2-digit",
                      })
                      : "-"}
                </strong>

              </div>
            </div>

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
                Human Review
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
                  Showing {filteredEmails.length} of {visibleEmails.length} emails
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
                Human Review
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

                    {visibleEmails.length === 0
                        ? "No Gmail messages have been loaded yet."
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
                                "PENDING_REVIEW" &&
                                email.aiReviewed && (

                                    <span className="review-needed">
                            Human Review
                          </span>

                                )}


                            {email.action ===
                                "PENDING_REVIEW" &&
                                !email.aiReviewed && (

                                    <span className="ai-queued">
                            AI Queue
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
                    !actionLoading &&
                    setSelectedEmail(null)
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
                        !actionLoading &&
                        setSelectedEmail(null)
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
                            {selectedEmail.aiReviewed
                                ? "Human Review Required"
                                : "Review Pending"}
                          </h3>


                          <p>
                            {selectedEmail.aiReviewed
                                ? "SmartMail reviewed this email but kept it for your decision because confidence was not high enough for an automatic action."
                                : "This email is still waiting for an AI or manual decision."}
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