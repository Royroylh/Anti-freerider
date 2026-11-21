(function () {
  "use strict";

  const signinView = document.getElementById("signin-view");
  const registerView = document.getElementById("register-view");
  const signinForm = document.getElementById("signin-form");
  const registerForm = document.getElementById("register-form");
  const alertBox = document.getElementById("auth-error");
  const invite = new URLSearchParams(window.location.search).get("invite");
  if (invite) localStorage.setItem("af.invite", invite);

  function showError(message) {
    alertBox.textContent = message;
    alertBox.hidden = false;
    alertBox.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }

  function clearError() {
    alertBox.hidden = true;
    alertBox.textContent = "";
  }

  function showRegister() {
    clearError();
    signinView.hidden = true;
    registerView.hidden = false;
    document.getElementById("register-name").focus();
    history.replaceState(null, "", "#register");
  }

  function showSignin() {
    clearError();
    registerView.hidden = true;
    signinView.hidden = false;
    document.getElementById("email").focus();
    history.replaceState(null, "", "#signin");
  }

  document.getElementById("show-register").addEventListener("click", function (event) {
    event.preventDefault();
    showRegister();
  });
  document.getElementById("show-signin").addEventListener("click", function (event) {
    event.preventDefault();
    showSignin();
  });

  async function post(form, endpoint) {
    const button = form.querySelector("button[type=submit]");
    const original = button.textContent;
    button.disabled = true;
    button.textContent = "Please wait…";
    clearError();
    const data = Object.fromEntries(new FormData(form).entries());
    data.remember = Boolean(data.remember);
    try {
      const response = await fetch(endpoint, {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data)
      });
      const payload = await response.json().catch(function () { return {}; });
      if (!response.ok) {
        showError(payload.error || "Could not complete this request.");
        return;
      }
      const pendingInvite = localStorage.getItem("af.invite");
      window.location.assign("/dashboard.html" + (pendingInvite ? "?invite=" + encodeURIComponent(pendingInvite) : ""));
    } catch (error) {
      showError("Network error. Is the local server still running?");
    } finally {
      button.disabled = false;
      button.textContent = original;
    }
  }

  signinForm.addEventListener("submit", function (event) {
    event.preventDefault();
    post(signinForm, "/api/auth/login");
  });
  registerForm.addEventListener("submit", function (event) {
    event.preventDefault();
    post(registerForm, "/api/auth/register");
  });

  if (window.location.hash === "#register") showRegister();
})();
