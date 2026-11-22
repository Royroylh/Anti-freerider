(function () {
  "use strict";

  const state = {
    me: null,
    projects: [],
    projectId: null,
    board: null,
    branchFilter: "all",
    openTaskId: null,
    reportPreview: null,
    evidenceContext: null
  };

  const $ = function (id) { return document.getElementById(id); };
  const STATUS_LABELS = {
    todo: "To do",
    needs_revision: "Needs revision",
    pending_review: "Pending review",
    blocked: "Blocked",
    follow_up: "Needs follow-up",
    accepted: "Accepted",
    working: "In progress",
    waiting_review: "Awaiting review",
    done: "Completed",
    idle: "No tasks",
    open: "Open",
    closed: "Closed",
    pending: "Pending review",
    rejected: "Rejected",
    withdrawn: "Withdrawn",
    superseded: "Superseded",
    approved: "Approved"
  };
  const STATUS_CLASSES = {
    pending_review: "working",
    blocked: "blocked",
    follow_up: "blocked",
    accepted: "done",
    waiting_review: "working",
    working: "working",
    todo: "working",
    needs_revision: "blocked",
    idle: "idle"
  };
  const PALETTE = ["teal", "blue", "amber", "violet"];

  function h(tag, attrs) {
    const node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        const value = attrs[key];
        if (value === null || value === undefined || value === false) return;
        if (key === "class") node.className = value;
        else if (key === "text") node.textContent = value;
        else if (key === "dataset") {
          Object.keys(value).forEach(function (k) { node.dataset[k] = value[k]; });
        } else if (key.startsWith("on") && typeof value === "function") {
          node.addEventListener(key.slice(2).toLowerCase(), value);
        } else if (value === true) node.setAttribute(key, "");
        else node.setAttribute(key, value);
      });
    }
    for (let i = 2; i < arguments.length; i++) {
      const child = arguments[i];
      if (child === null || child === undefined || child === false) continue;
      if (Array.isArray(child)) child.forEach(function (c) { if (c !== null && c !== undefined) node.append(c); });
      else if (child instanceof Node) node.append(child);
      else node.append(document.createTextNode(String(child)));
    }
    return node;
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function add(parent) {
    for (let i = 1; i < arguments.length; i++) {
      const child = arguments[i];
      if (child === null || child === undefined) continue;
      parent.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return parent;
  }

  function fmtDate(iso, withTime) {
    if (!iso) return "—";
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return iso;
    const opts = { month: "short", day: "numeric" };
    if (withTime) {
      opts.hour = "2-digit";
      opts.minute = "2-digit";
    }
    return date.toLocaleString("en-US", opts);
  }

  function fmtDateTime(iso) {
    if (!iso) return "—";
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return iso;
    return date.toLocaleString("en-US", {
      year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit"
    });
  }

  function fmtTime(iso) {
    if (!iso) return "—";
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return iso;
    return date.toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit" });
  }

  function fmtNumber(value) {
    if (value === null || value === undefined) return "0";
    return Math.abs(value - Math.round(value)) < 0.001 ? String(Math.round(value)) : Number(value).toFixed(1);
  }

  function initials(name) {
    if (!name) return "?";
    const parts = name.trim().split(/\s+/);
    if (parts.length > 1) return (Array.from(parts[0])[0] + Array.from(parts[1])[0]).toUpperCase();
    return Array.from(name).slice(0, 2).join("").toUpperCase();
  }

  async function api(path, options) {
    const opts = options || {};
    const response = await fetch(path, {
      method: opts.method || "GET",
      signal: opts.signal,
      credentials: "same-origin",
      headers: opts.body ? { "Content-Type": "application/json" } : {},
      body: opts.body ? JSON.stringify(opts.body) : undefined
    });
    if (response.status === 401) {
      window.location.assign("/");
      throw new Error("Sign in required");
    }
    const payload = await response.json().catch(function () { return { error: "Invalid server response" }; });
    if (!response.ok) throw new Error(payload.error || ("Request failed (" + response.status + ")"));
    return payload;
  }

  let toastTimer = null;
  function toast(message, kind) {
    const box = $("toast");
    box.textContent = message;
    box.className = "toast" + (kind ? " " + kind : "");
    box.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { box.hidden = true; }, 4200);
  }

  function showError(message) {
    const box = $("global-error");
    box.textContent = message;
    box.hidden = false;
  }

  function hideError() {
    $("global-error").hidden = true;
  }

  function currentProject() {
    return state.projects.find(function (p) { return p.id === state.projectId; }) || null;
  }

  function taskById(id) {
    if (!state.board) return null;
    return state.board.tasks.find(function (t) { return t.id === id; }) || null;
  }

  function memberById(id) {
    if (!state.board) return null;
    return state.board.members.find(function (m) { return m.id === id; }) || null;
  }

  function statusPill(stateValue, extra) {
    return h("span", { class: "status-pill " + (STATUS_CLASSES[stateValue] || "working") + (extra ? " " + extra : "") },
      STATUS_LABELS[stateValue] || stateValue);
  }

  async function boot() {
    bindStatic();
    try {
      const me = await api("/api/me");
      state.me = me.user;
      $("profile-avatar").textContent = initials(state.me.name);
      $("profile-name").textContent = state.me.name;
    } catch (error) {
      return;
    }
    const params = new URLSearchParams(window.location.search);
    await refreshProjects();
    if (params.get("task")) {
      openTask(Number(params.get("task")), Number(params.get("submission")) || null);
    }
    if (params.get("invite")) {
      showJoinDialog(params.get("invite"));
    }
  }

  function bindStatic() {
    $("signout-button").addEventListener("click", async function () {
      stopChat();
      signingOut = true;
      try {
        await api("/api/auth/logout", { method: "POST", body: {} });
      } catch (error) {
        // Even if the request fails, returning to sign-in is safe.
      }
      window.location.assign("/");
    });
    $("new-project-button").addEventListener("click", function () {
      $("create-project-form").reset();
      $("create-project-form").elements.plannedPoints.value = "100";
      $("create-project-form").elements.branchName.value = "General";
      openDialog("project-dialog");
    });
    $("empty-create").addEventListener("click", function () {
      $("create-project-form").reset();
      $("create-project-form").elements.plannedPoints.value = "100";
      $("create-project-form").elements.branchName.value = "General";
      openDialog("project-dialog");
    });
    $("empty-join").addEventListener("click", function () { showJoinDialog(""); });
    $("assistant-button").addEventListener("click", openAssistant);
    $("assistant-close").addEventListener("click", function () { closeAssistant(); });
    $("assistant-backdrop").addEventListener("click", function () { closeAssistant(); });
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && !$("assistant-panel").hidden) closeAssistant();
    });
    $("join-project-button").addEventListener("click", function () { showJoinDialog(""); });
    $("join-project-form").elements.inviteCode.addEventListener("input", function (event) {
      scheduleInvitePreview(event.currentTarget.value);
    });
    $("settings-button").addEventListener("click", openSettings);
    $("add-task-button").addEventListener("click", function () {
      if (state.projectId) openTaskCreate();
    });
    $("preview-report-button").addEventListener("click", previewReport);
    document.addEventListener("visibilitychange", function () {
      stopChat();
      if (!document.hidden) scheduleChat(0);
    });
    window.addEventListener("pagehide", stopChat);
    window.addEventListener("pageshow", function (event) {
      if (event.persisted) scheduleChat(0);
    });
    $("chat-form").addEventListener("submit", async function (event) {
      event.preventDefault();
      const form = event.currentTarget;
      const projectId = state.projectId;
      const original = form.elements.body.value;
      const taskId = Number(form.elements.taskId.value) || 0;
      if (!original.trim() || state.loadedProjectId !== projectId) return;
      const send = event.submitter;
      if (send) send.disabled = true;
      try {
        await api("/api/projects/" + projectId + "/messages", {
          method: "POST", body: { body: original.trim(), taskId: taskId }
        });
        if (state.projectId === projectId) {
          if (form.elements.body.value === original) form.elements.body.value = "";
          stopChat();
          scheduleChat(0);
          toast("Message posted.");
        } else if (chatDrafts.get(projectId)?.body === original) {
          chatDrafts.get(projectId).body = "";
        }
      } catch (error) {
        if (state.projectId === projectId) toast(error.message, "error");
      } finally {
        if (send) send.disabled = false;
      }
    });
    $("ai-form").addEventListener("submit", async function (event) {
      event.preventDefault();
      const question = event.currentTarget.elements.question.value.trim();
      if (question) await askEvidence(question);
    });
    document.querySelectorAll("[data-question]").forEach(function (button) {
      button.addEventListener("click", function () {
        $("ai-question").value = button.dataset.question;
        askEvidence(button.dataset.question);
      });
    });
    $("create-project-form").addEventListener("submit", async function (event) {
      event.preventDefault();
      await guard(async function () {
        const form = event.currentTarget;
        const data = Object.fromEntries(new FormData(form).entries());
        data.plannedPoints = Number(data.plannedPoints);
        const project = await api("/api/projects", { method: "POST", body: data });
        closeDialog("project-dialog");
        await refreshProjects(project.id);
        toast("Project created. Share the invite code from Project settings.");
      });
    });
    $("join-project-form").addEventListener("submit", async function (event) {
      event.preventDefault();
      await guard(async function () {
        const form = event.currentTarget;
        const data = Object.fromEntries(new FormData(form).entries());
        const project = await api("/api/projects/join", { method: "POST", body: data });
        localStorage.removeItem("af.invite");
        closeDialog("join-dialog");
        await refreshProjects(project.id);
        toast("Joined " + project.name + ".");
      });
    });
    $("create-task-form").addEventListener("submit", createTask);
    $("create-branch-form").addEventListener("submit", async function (event) {
      event.preventDefault();
      await guard(async function () {
        const form = event.currentTarget;
        const data = Object.fromEntries(new FormData(form).entries());
        data.plannedPoints = Number(data.plannedPoints);
        await api("/api/projects/" + state.projectId + "/branches", { method: "POST", body: data });
        closeDialog("branch-dialog");
        await loadBoard();
        await openSettings();
        toast("Branch created.");
      });
    });
    document.querySelectorAll("[data-close]").forEach(function (button) {
      button.addEventListener("click", function () { closeDialog(button.dataset.close); });
    });
    ["project-dialog", "join-dialog", "task-create-dialog", "branch-dialog", "settings-dialog",
      "report-dialog", "task-dialog"].forEach(function (id) {
      $(id).addEventListener("click", function (event) {
        if (event.target === $(id) && id !== "task-dialog") closeDialog(id);
      });
    });
    $("task-dialog").addEventListener("close", function () {
      if ($("task-dialog").open) return;
      state.openTaskId = null;
      updateUrl({ task: null, submission: null });
    });
    document.querySelectorAll(".export-options input").forEach(function (input) {
      input.addEventListener("change", updateDownloadLink);
    });
  }

  async function guard(fn) {
    hideError();
    try {
      await fn();
    } catch (error) {
      if (error.message !== "Sign in required") {
        showError(error.message);
        toast(error.message, "error");
      }
    }
  }

  async function refreshProjects(preferredId) {
    const data = await api("/api/projects");
    state.projects = data.projects;
    renderProfile();
    if (!state.projects.length) {
      switchChatProject(null);
      state.projectId = null;
      state.loadedProjectId = null;
      state.board = null;
      renderProjectList();
      renderNoProjects();
      return;
    }
    const params = new URLSearchParams(window.location.search);
    const fromUrl = Number(params.get("project"));
    const stored = Number(localStorage.getItem("af.lastProject"));
    let target = preferredId || fromUrl || stored || state.projects[0].id;
    if (!state.projects.some(function (p) { return p.id === target; })) target = state.projects[0].id;
    switchChatProject(target);
    state.projectId = target;
    renderProjectList();
    await loadBoard();
  }

  function renderProfile() {
    const project = currentProject();
    if (!state.me) return;
    $("profile-avatar").textContent = initials(state.me.name);
    $("profile-avatar").className = "avatar";
    $("profile-name").textContent = state.me.name;
    $("profile-role").textContent = project
      ? (project.isOwner ? "Project owner · " + project.name : "Student member · " + project.name)
      : "Student member";
  }

  function renderProjectList() {
    const list = $("project-list");
    clear(list);
    $("project-count").textContent = String(state.projects.length);
    state.projects.forEach(function (project, index) {
      const active = project.id === state.projectId;
      const button = h("button", {
        class: "project-item" + (active ? " is-active" : ""),
        type: "button",
        title: project.name,
        "aria-current": active ? "page" : null,
        onclick: function () { selectProject(project.id); }
      },
        h("span", { class: "project-icon " + PALETTE[index % PALETTE.length], "aria-hidden": "true" },
          initials(project.name).slice(0, 1)),
        h("span", { class: "project-copy" },
          h("strong", { text: project.name }),
          h("small", {
            text: project.memberCount + " members · " +
              (project.hasAccepted ? fmtNumber(project.completionPct) + "% complete" : "No accepted contributions yet")
          })),
        h("span", { class: "status-dot" + (active ? " active" : ""), "aria-hidden": active ? null : "true" }));
      list.append(button);
    });
    renderProfile();
  }

  async function selectProject(id) {
    if (state.projectId === id && state.board) {
      closeDialog("task-dialog");
      return;
    }
    closeDialog("task-dialog");
    closeDialog("report-dialog");
    closeAssistant(true);
    state.openTaskId = null;
    switchChatProject(id);
    state.projectId = id;
    state.branchFilter = "all";
    resetProjectTools();
    localStorage.setItem("af.lastProject", String(id));
    updateUrl({ project: id, task: null, submission: null });
    renderProjectList();
    await loadBoard();
  }

  function resetProjectTools() {
    state.evidenceContext = null;
    updateEvidenceContext();
    const answer = $("ai-answer");
    if (answer) {
      answer.hidden = true;
      clear(answer);
    }
    const question = $("ai-question");
    if (question) question.value = "";
    const preview = $("report-preview");
    if (preview) {
      preview.hidden = true;
      clear(preview);
    }
    state.reportPreview = null;
  }

  let boardRequest = 0;
  async function loadBoard() {
    if (!state.projectId) return;
    const requestedProject = state.projectId;
    const request = ++boardRequest;
    if (state.loadedProjectId !== requestedProject) {
      resetProjectTools();
    }
    try {
      const board = await api("/api/projects/" + requestedProject + "/board");
      if (state.projectId !== requestedProject || request !== boardRequest) return;
      const changed = state.loadedProjectId !== requestedProject;
      if (!changed && chatProjectRendered === requestedProject) board.messages = state.board.messages;
      state.board = board;
      state.loadedProjectId = requestedProject;
      renderAll();
      scheduleChat(changed ? 0 : 4000);
      localStorage.setItem("af.lastProject", String(state.projectId));
    } catch (error) {
      if (state.projectId === requestedProject && request === boardRequest) showError(error.message);
    }
  }

  function updateUrl(values) {
    const url = new URL(window.location.href);
    Object.keys(values).forEach(function (key) {
      if (values[key] === null || values[key] === undefined || values[key] === "") url.searchParams.delete(key);
      else url.searchParams.set(key, String(values[key]));
    });
    history.replaceState(null, "", url.pathname + "?" + url.searchParams.toString());
  }

  function renderAll() {
    if (!state.board) return;
    setWorkspaceVisible(true);
    renderHeader();
    renderStats();
    renderMemberBoard();
    renderBranchFilter();
    renderTimeline();
    renderCards();
    renderChat();
    renderAiStatus();
    renderReports();
    updateDownloadLink();
    $("updated-label").textContent = "Updated " + fmtDate(state.board.serverTime, true);
    hideError();
  }

  function renderNoProjects() {
    resetProjectTools();
    closeAssistant();
    setWorkspaceVisible(false);
    $("project-title").textContent = "No projects yet";
    $("project-description").textContent = "Create a group, or join one with an invite code a teammate shared.";
    $("avatar-stack").replaceChildren();
    ["member-board", "timeline", "todo-list", "review-list", "problem-list", "help-list", "chat-list",
      "reports-list", "report-preview"].forEach(function (id) { clear($(id)); });
    ["todo-count", "review-count", "problem-count", "help-count"].forEach(function (id) { $(id).textContent = "0"; });
    $("stat-completion").textContent = "—";
    $("stat-points").textContent = "No accepted work yet";
    $("stat-checkpoint").textContent = "—";
    $("stat-checkpoint-note").textContent = "No checkpoint recorded";
    $("stat-my-points").textContent = "—";
    $("stat-my-share").textContent = "No accepted contributions yet";
  }

  function renderHeader() {
    const project = state.board.project;
    $("project-title").textContent = project.name;
    $("project-description").textContent = project.description || "Team progress and accountability workspace";
    const stack = $("avatar-stack");
    clear(stack);
    const members = state.board.members;
    members.slice(0, 4).forEach(function (member) {
      stack.append(h("span", { text: initials(member.name), title: member.name }));
    });
    if (members.length > 4) stack.append(h("span", { text: "+" + (members.length - 4) }));
    stack.setAttribute("aria-label", members.length + " project members");
    $("settings-button").disabled = false;
    $("add-task-button").disabled = false;
    $("assistant-button").disabled = false;
  }

  function renderStats() {
    const contribution = state.board.contribution;
    const me = contribution.members.find(function (m) { return m.id === state.me.id; });
    $("stat-completion").textContent = contribution.hasAccepted ? fmtNumber(contribution.completionPct) + "%" : "0%";
    $("stat-completion").setAttribute("aria-label",
      "Project completion " + fmtNumber(contribution.completionPct) + " percent, " +
      contribution.acceptedPoints + " of " + contribution.plannedPoints + " points accepted");
    $("stat-points").textContent = contribution.acceptedPoints + " / " + contribution.plannedPoints + " points accepted"
      + (contribution.remainingPoints < 0 ? " · Over budget by " + -contribution.remainingPoints + " points. Revise the plan" : "");
    $("stat-my-points").textContent = contribution.hasAccepted ? fmtNumber(me ? me.points : 0) : "—";
    $("stat-my-share").textContent = contribution.hasAccepted
      ? fmtNumber(me ? me.points : 0) + " / " + contribution.acceptedPoints + " = " +
        fmtNumber(me ? me.sharePct : 0) + "% of accepted work"
      : "No accepted contributions yet";
    const pending = state.board.tasks.filter(function (task) {
      return !task.accepted && task.checkpointAt;
    });
    const now = new Date(state.board.serverTime).getTime();
    pending.sort(function (a, b) {
      return new Date(a.checkpointAt) - new Date(b.checkpointAt);
    });
    const next = pending.find(function (task) {
      return new Date(task.checkpointAt).getTime() >= now;
    }) || pending[pending.length - 1];
    if (next) {
      $("stat-checkpoint").textContent = fmtDate(next.checkpointAt, true);
      $("stat-checkpoint-note").textContent = next.title + " · " + (STATUS_LABELS[next.state] || next.state);
      $("stat-checkpoint").setAttribute("aria-label", "Recent checkpoint " + fmtDateTime(next.checkpointAt) +
        " for " + next.title);
    } else {
      $("stat-checkpoint").textContent = "—";
      $("stat-checkpoint-note").textContent = "No checkpoint recorded";
    }
  }

  function renderMemberBoard() {
    const container = $("member-board");
    clear(container);
    if (!state.board.memberRows.length) {
      container.append(h("p", { class: "empty-state", text: "No members yet." }));
      return;
    }
    state.board.memberRows.forEach(function (row, index) {
      const active = row.activeTasks && row.activeTasks.length
        ? row.activeTasks
        : (row.currentTaskId ? [{
          id: row.currentTaskId,
          title: row.currentTaskTitle,
          state: row.currentTaskState,
          checkpointAt: row.nextCheckpoint,
          deadlineAt: row.deadlineAt
        }] : []);
      const article = h("article", {
        class: "member-row",
        "aria-label": row.name + ", " + (STATUS_LABELS[row.status] || row.status) +
          (active.length ? ", " + active.length + " active tasks" : ", no active task")
      });
      article.append(h("span", { class: "mini-avatar " + PALETTE[index % PALETTE.length], text: row.initials }));
      const main = h("div", { class: "member-main" });
      const line = h("div", { class: "member-line" });
      line.append(h("strong", { text: row.name }));
      if (row.id === state.me.id) line.append(h("span", { class: "self-tag", text: "you" }));
      line.append(statusPill(row.status));
      main.append(line);
      if (active.length) {
        const first = active[0];
        main.append(h("p", { class: "member-task" },
          h("button", {
            class: "link-button task-link",
            type: "button",
            text: first.title,
            onclick: function () { openTask(first.id); }
          }),
          active.length > 1 ? h("span", { class: "active-count", text: " · " + (active.length - 1) + " more" }) : null));
        if (active.length > 1) {
          const more = h("details", { class: "member-more" },
            h("summary", { text: "Plus " + (active.length - 1) + " tasks / show all" }));
          const list = h("ul", { class: "member-task-list" });
          active.slice(1).forEach(function (task) {
            list.append(h("li", null,
              h("button", {
                class: "link-button", type: "button", text: task.title,
                onclick: function () { openTask(task.id); }
              }),
              h("span", { class: "muted", text: " · " + (STATUS_LABELS[task.state] || task.state) +
                " · checkpoint " + fmtDateTime(task.checkpointAt) })));
          });
          more.append(list);
          main.append(more);
        }
      } else {
        main.append(h("p", { class: "member-task muted", text: "No active task" }));
      }
      const meta = h("div", { class: "member-meta" });
      if (row.nextCheckpoint) {
        meta.append(h("span", { text: "Next checkpoint " + fmtDateTime(row.nextCheckpoint) }));
      }
      if (row.deadlineAt) meta.append(h("span", { text: "Deadline " + fmtDate(row.deadlineAt, true) }));
      main.append(meta);
      if (row.neededSupport) {
        const supportBlock = h("div", { class: "support-note" },
          h("strong", { text: row.status === "blocked" ? "Blocked — needed support: " : "Support requested: " }),
          h("span", { text: row.neededSupport }),
          row.supportBody ? h("p", { class: "muted", text: row.supportBody }) : null);
        if (row.supportTaskId) {
          supportBlock.append(h("button", {
            class: "link-button support-task-link",
            type: "button",
            text: "Task: " + (row.supportTaskTitle || ("#" + row.supportTaskId)),
            onclick: function () { openTask(row.supportTaskId); }
          }));
        }
        main.append(supportBlock);
      }
      if (row.privateReminder) {
        main.append(h("div", { class: "private-reminder" },
          h("strong", { text: "Private reminder" }),
          h("span", {
            text: " Checkpoint passed " + fmtDateTime(row.privateReminder.checkpointAt) +
              (row.privateReminder.taskTitle ? " on " + row.privateReminder.taskTitle : "") +
              ". Only you can see this private reminder. The group sees a follow-up flag only after 24 hours without a response."
          })));
      }
      article.append(main);
      container.append(article);
    });
  }

  function renderBranchFilter() {
    const container = $("branch-filter");
    clear(container);
    if (!state.board.branches.some(function (b) { return b.id === state.branchFilter; })) state.branchFilter = "all";
    const options = [{ id: "all", name: "All branches" }].concat(state.board.branches);
    options.forEach(function (branch) {
      container.append(h("button", {
        class: "filter-chip" + (String(branch.id) === String(state.branchFilter) ? " is-active" : ""),
        type: "button",
        text: branch.name,
        onclick: function () {
          state.branchFilter = branch.id;
          renderBranchFilter();
          renderTimeline();
        }
      }));
    });
  }

  function renderTimeline() {
    const container = $("timeline");
    clear(container);
    const branchId = state.branchFilter === "all" ? null : Number(state.branchFilter);
    const branch = state.board.branches.find(function (item) { return item.id === branchId; });
    if (branch) {
      container.append(h("p", { class: "muted-note", text: branch.acceptedPoints + " / " + branch.plannedPoints
        + " = " + fmtNumber(branch.completionPct) + "% · " + budgetText(branch) }));
    }
    const entries = state.board.timeline.filter(function (entry) {
      return branchId === null || entry.branchId === branchId;
    });
    if (!entries.length) {
      container.append(h("p", { class: "empty-state", text: "No accepted work yet." }));
      return;
    }
    const groups = new Map();
    entries.forEach(function (entry) {
      const date = new Date(entry.acceptedAt);
      const key = Number.isNaN(date.getTime()) ? entry.acceptedAt : date.toLocaleDateString();
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(entry);
    });
    groups.forEach(function (groupEntries, key) {
      const group = h("section", { class: "timeline-group", "aria-label": "Accepted work on " + key });
      group.append(h("h3", { class: "timeline-date", text: key }));
      const list = h("div", { class: "timeline-group-items" });
      groupEntries.forEach(function (entry) {
        const item = h("article", { class: "timeline-item complete" },
          h("time", { datetime: entry.acceptedAt, text: fmtTime(entry.acceptedAt) }),
          h("span", { class: "timeline-marker", "aria-hidden": "true", text: "✓" }),
          h("div", { class: "timeline-content" },
            h("div", { class: "member-line" },
              h("span", { class: "mini-avatar", text: initials(entry.authors[0] ? entry.authors[0].name : "AC") }),
              h("strong", { text: entry.title }),
              h("span", { class: "status-pill done", text: "Accepted" })),
            h("p", {
              text: entry.branchName + " · Version v" + entry.version + " · " + entry.points + " points · Review " +
                fmtDate(entry.acceptedAt, true)
            }),
            h("p", { class: "timeline-authors" },
              (function () {
                const wrap = h("span");
                entry.authors.forEach(function (author, index) {
                  if (index) wrap.append(h("span", { class: "dot-sep", text: " · " }));
                  wrap.append(h("span", {
                    text: author.name + " " + fmtNumber(author.sharePct) + "% · " + fmtNumber(author.points) + " points"
                  }));
                });
                return wrap;
              })()),
            h("button", {
              class: "link-button", type: "button", text: "Open task and version →",
              onclick: function () { openTask(entry.taskId, entry.submissionId); }
            })));
        list.append(item);
      });
      group.append(list);
      container.append(group);
    });
  }

  function renderCards() {
    const tasks = state.board.tasks;
    const todo = tasks.filter(function (t) {
      return ["todo", "needs_revision"].includes(t.state);
    });
    const review = tasks.filter(function (t) { return t.state === "pending_review"; });
    const problems = tasks.filter(function (t) { return t.state === "blocked" || t.state === "follow_up"; });
    const helps = tasks.filter(function (t) { return t.support && t.support.kind === "help"; });
    renderTodo(todo);
    renderReview(review);
    renderProblems(problems);
    renderHelp(helps);
    $("todo-count").textContent = String(todo.length);
    $("review-count").textContent = String(review.length);
    $("problem-count").textContent = String(problems.length);
    $("help-count").textContent = String(helps.length);
  }

  function emptyRow(text) {
    return h("p", { class: "empty-state", text: text });
  }

  function taskMeta(task) {
    return h("small", {
      text: fmtDate(task.deadlineAt, true) + " · " + task.branchName + " · " + task.points + " points · " +
        task.assigneeName
    });
  }

  function renderTodo(tasks) {
    const list = $("todo-list");
    clear(list);
    if (!tasks.length) return list.append(emptyRow("No tasks to do."));
    tasks.forEach(function (task) {
      list.append(h("div", { class: "task-row clickable", onclick: function () { openTask(task.id); } },
        h("span", { class: "task-check", "aria-hidden": "true" }),
        h("div", null,
          h("strong", { text: task.title }),
          taskMeta(task),
          task.state === "needs_revision"
            ? h("span", { class: "priority medium", text: "A revised version is needed" })
            : null),
        h("span", { class: "priority " + (task.state === "needs_revision" ? "medium" : "low"), text: task.state === "needs_revision" ? "Revision" : "To do" })));
    });
  }

  function renderReview(tasks) {
    const list = $("review-list");
    clear(list);
    if (!tasks.length) return list.append(emptyRow("No submissions awaiting review."));
    tasks.forEach(function (task) {
      const latest = task.latestSubmission || {};
      const actions = task.latestActions || {};
      const needsYou = actions.canConfirm || actions.canVote;
      list.append(h("button", {
        class: "compact-item clickable" + (needsYou ? " needs-you" : ""),
        type: "button",
        onclick: function () { openTask(task.id); }
      },
        h("span", { class: "mini-avatar blue", text: initials(task.assigneeName) }),
        h("div", null,
          h("strong", { text: task.title }),
          h("small", {
            text: "Version v" + latest.version + " · Pending review · Submitted by " + (latest.submittedBy || "member")
          }),
          needsYou ? h("span", { class: "needs-you-tag", text: "Your action is needed" }) : null)));
    });
  }

  function renderProblems(tasks) {
    const list = $("problem-list");
    clear(list);
    if (!tasks.length) return list.append(emptyRow("No blocked tasks or outstanding follow-ups."));
    tasks.forEach(function (task) {
      const support = task.support;
      list.append(h("button", {
        class: "message-item clickable", type: "button", onclick: function () { openTask(task.id); }
      },
        h("div", { class: "message-title" },
          h("strong", { text: task.title }),
          statusPill(task.state)),
        support
          ? h("p", { text: support.body })
          : h("p", { text: "No submission, update or help request within 24 hours of the first reminder." }),
        support ? h("small", { text: "Support needed: " + support.neededSupport + " · " + support.userName }) : null,
        task.followUpData
          ? h("small", { text: "Follow-up flag raised at " + fmtDate(task.followUpData.at, true) })
          : null));
    });
  }

  function renderHelp(tasks) {
    const list = $("help-list");
    clear(list);
    if (!tasks.length) return list.append(emptyRow("No open help requests."));
    tasks.forEach(function (task) {
      list.append(h("button", {
        class: "message-item clickable", type: "button", onclick: function () { openTask(task.id); }
      },
        h("div", { class: "member-line" },
          h("span", { class: "mini-avatar amber", text: initials(task.support.userName) }),
          h("strong", { text: task.support.userName })),
        h("p", { text: task.support.body }),
        h("small", { text: "Support needed: " + task.support.neededSupport + " · " + task.title })));
    });
  }

  let chatTimer = null;
  let chatRequest = null;
  let chatProjectRendered = null;
  let signingOut = false;
  const chatDrafts = new Map();

  function stopChat() {
    clearTimeout(chatTimer);
    chatTimer = null;
    if (chatRequest) chatRequest.abort();
    chatRequest = null;
  }

  function switchChatProject(next) {
    if (next === state.projectId) return;
    stopChat();
    const form = $("chat-form");
    if (state.projectId) {
      chatDrafts.set(state.projectId, { body: form.elements.body.value, task: form.elements.taskId.value });
    }
    chatProjectRendered = null;
    $("chat-list").replaceChildren();
    form.elements.body.value = "";
    form.elements.taskId.replaceChildren();
  }

  function scheduleChat(delay) {
    if (signingOut || document.hidden || chatTimer !== null || chatRequest ||
        !state.projectId || state.loadedProjectId !== state.projectId) return;
    chatTimer = setTimeout(pollChat, delay);
  }

  async function pollChat() {
    chatTimer = null;
    const project = state.projectId;
    if (signingOut || document.hidden || project !== state.loadedProjectId) return;
    const controller = new AbortController();
    chatRequest = controller;
    try {
      const response = await api("/api/projects/" + project + "/messages", { signal: controller.signal });
      if (chatRequest === controller && state.projectId === project) {
        state.board.messages = response.messages;
        renderChatMessages();
      }
    } catch (error) {
      // A temporary connection failure can be retried by the next poll.
    } finally {
      if (chatRequest === controller) {
        chatRequest = null;
        scheduleChat(4000);
      }
    }
  }

  function renderChat() {
    const select = $("chat-task");
    const form = $("chat-form");
    const saved = chatDrafts.get(state.projectId);
    const firstRender = chatProjectRendered !== state.projectId;
    const previous = (firstRender && saved ? saved.task : select.value) || "0";
    if (firstRender && saved) form.elements.body.value = saved.body;
    chatProjectRendered = state.projectId;

    select.replaceChildren(h("option", { value: "0", text: "No linked task" }));
    state.board.tasks.forEach(function (task) {
      select.append(h("option", { value: String(task.id), text: task.title }));
    });
    if (Array.from(select.options).some(function (option) { return option.value === previous; })) select.value = previous;
    renderChatMessages();
  }

  function renderChatMessages() {
    const list = $("chat-list");
    const messages = state.board.messages.slice().reverse();
    const nodes = Array.from(list.children);
    if (!messages.length) {
      if (!nodes.length || nodes[0].dataset.messageId) list.replaceChildren(emptyRow("No chat messages yet."));
      return;
    }
    if (nodes.length === messages.length && nodes.every(function (node, index) {
      return node.dataset.messageId === String(messages[index].id);
    })) return;
    const visible = list.getBoundingClientRect();
    const anchor = nodes.find(function (node) { return node.getBoundingClientRect().bottom > visible.top; });
    const offset = anchor ? anchor.getBoundingClientRect().top : 0;
    const atEnd = list.scrollHeight - list.clientHeight - list.scrollTop < 4;
    const oldNodes = new Map(nodes.map(function (node) { return [Number(node.dataset.messageId), node]; }));
    messages.forEach(function (message, index) {
      const item = oldNodes.get(message.id) || chatMessage(message);
      if (list.children[index] !== item) list.insertBefore(item, list.children[index] || null);
      oldNodes.delete(message.id);
    });
    oldNodes.forEach(function (node) { node.remove(); });
    if (atEnd) list.scrollTop = list.scrollHeight;
    else if (anchor && anchor.isConnected) list.scrollTop += anchor.getBoundingClientRect().top - offset;
  }

  function chatMessage(message) {
    const item = h("article", { class: "chat-message", dataset: { messageId: String(message.id) } },
      h("div", { class: "chat-meta" },
        h("strong", { text: message.authorName }),
        h("span", { text: fmtDate(message.createdAt, true) })),
      h("p", { text: message.body }));
    if (message.taskId) {
      item.append(h("button", {
        class: "task-chip", type: "button", text: "Task: " + (message.taskTitle || ("#" + message.taskId)),
        onclick: function () { openTask(message.taskId); }
      }));
    }
    return item;
  }

  function renderAiStatus() {
    const status = state.board.aiStatus;
    const badge = $("ai-mode-badge");
    badge.textContent = status.mode === "local-model" ? "local model" : status.mode === "test-double" ? "test double" : "evidence retrieval";
    badge.className = "mode-badge " + (status.mode === "local-model" ? "local" : status.mode === "test-double" ? "test" : "fallback");
    $("ai-mode-note").textContent = status.label + " — " + status.note;
    $("assistant-button").title = status.label;
  }

  function renderReports() {
    const list = $("reports-list");
    clear(list);
    if (!state.board.reports.length) {
      list.append(emptyRow("No disputes. A rejected submission does not open a dispute automatically."));
      return;
    }
    state.board.reports.forEach(function (report) {
      list.append(h("button", {
        class: "compact-item clickable", type: "button", onclick: function () { openReport(report.id); }
      },
        h("span", { class: "mini-avatar violet", text: "!" }),
        h("div", null,
          h("strong", { text: "Dispute #" + report.id + " · " + report.taskTitle }),
          h("small", { text: report.reporter + " · " + fmtDate(report.createdAt, true) + " · " + (STATUS_LABELS[report.status] || report.status) }))));
    });
  }

  function updateEvidenceContext() {
    const label = $("ai-context");
    label.hidden = !state.evidenceContext;
    if (state.evidenceContext) {
      label.textContent = state.evidenceContext.title;
    }
  }

  async function askEvidence(question) {
    if ($("assistant-panel").hidden) openAssistant();
    const box = $("ai-answer");
    const requestedProject = state.projectId;
    box.hidden = false;
    box.replaceChildren(h("p", { class: "muted", text: "Retrieving evidence from this project…" }));
    try {
      const response = await api("/api/projects/" + requestedProject + "/ai", {
        method: "POST", body: {
          question: question,
          taskId: state.evidenceContext?.taskId || 0,
          submissionId: state.evidenceContext?.submissionId || 0
        }
      });
      if (state.projectId !== requestedProject) return;
      renderAiAnswer(response);
    } catch (error) {
      if (state.projectId === requestedProject) {
        box.replaceChildren(h("p", { class: "error-text", text: error.message }));
      }
    }
  }

  function renderAiAnswer(response) {
    const box = $("ai-answer");
    clear(box);
    box.append(h("div", { class: "answer-head" },
      h("span", { class: "mode-badge " + (response.mode === "local-model" ? "local" : response.mode === "test-double" ? "test" : "fallback"),
        text: response.modeLabel }),
      h("span", { class: "muted-note", text: "votes excluded · chat excluded" })));
    box.append(h("h3", { class: "answer-heading", text: "Answer" }));
    box.append(h("pre", { class: "answer-text", text: response.answer }));
    if (response.modelError) box.append(h("p", { class: "error-text", text: response.modelError }));
    if (response.citations && response.citations.length) {
      const list = h("div", { class: "citation-list" });
      list.append(h("p", { class: "eyebrow", text: "Citations" }));
      response.citations.forEach(function (citation) {
        list.append(h("a", {
          class: "citation-link",
          href: citation.url,
          text: "[" + citation.id + "] " + citation.label,
          onclick: function (event) {
            event.preventDefault();
            handleCitation(citation);
          }
        }));
      });
      box.append(list);
    }
    box.append(h("p", { class: "muted-note", text: "Contribution numbers are calculated by the server; the explanation never creates them." }));
    box.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }

  async function handleCitation(citation) {
    if (citation.projectId !== state.projectId) {
      await selectProject(citation.projectId);
    }
    if (citation.taskId) {
      await openTask(citation.taskId, citation.submissionId || null);
      let target = null;
      if (citation.submissionId) target = "submission-" + citation.submissionId;
      else if (citation.supportId) target = "support-" + citation.supportId;
      else if (citation.dateChangeId) target = "date-change-" + citation.dateChangeId;
      if (target) {
        requestAnimationFrame(function () {
          const node = document.getElementById(target);
          if (node) {
            node.classList.add("highlight");
            node.scrollIntoView({ behavior: "smooth", block: "center" });
          }
        });
      }
    }
  }

  function buildExportUrl() {
    if (!state.projectId) return "#";
    const params = new URLSearchParams();
    params.set("tasks", $("export-tasks").checked ? "1" : "0");
    params.set("timeline", $("export-timeline").checked ? "1" : "0");
    params.set("disputes", $("export-disputes").checked ? "1" : "0");
    params.set("reminders", $("export-reminders").checked ? "1" : "0");
    return "/api/projects/" + state.projectId + "/report/export?" + params.toString();
  }

  function updateDownloadLink() {
    const link = $("download-report-link");
    if (link) link.href = buildExportUrl();
    if (state.reportPreview && !$("report-preview").hidden) previewReport();
  }

  async function previewReport() {
    if (!state.projectId) return;
    const requestedProject = state.projectId;
    const box = $("report-preview");
    box.hidden = false;
    box.replaceChildren(h("p", { class: "muted", text: "Calculating from accepted records…" }));
    try {
      const params = new URLSearchParams();
      params.set("tasks", $("export-tasks").checked ? "1" : "0");
      params.set("timeline", $("export-timeline").checked ? "1" : "0");
      params.set("disputes", $("export-disputes").checked ? "1" : "0");
      params.set("reminders", $("export-reminders").checked ? "1" : "0");
      const data = await api("/api/projects/" + requestedProject + "/report?" + params.toString());
      if (state.projectId !== requestedProject) return;
      state.reportPreview = data;
      renderReportPreview(data);
    } catch (error) {
      if (state.projectId === requestedProject) {
        box.replaceChildren(h("p", { class: "error-text", text: error.message }));
      }
    }
  }

  function renderReportPreview(data) {
    const box = $("report-preview");
    clear(box);
    const contribution = data.contribution;
    const achievements = new Map((data.memberAchievements || []).map(function (member) {
      return [member.id, member.items || []];
    }));
    box.append(h("h3", { text: "Contribution summary" }));
    box.append(h("p", {
      text: "Project completion: " + contribution.acceptedPoints + " / " + contribution.plannedPoints +
        " = " + fmtNumber(contribution.completionPct) + "% (accepted task points / planned points)."
    }));
    box.append(h("p", { class: "muted-note", text: budgetText(contribution) }));
    const table = h("table", { class: "report-table" },
      h("thead", null, h("tr", null,
        h("th", { text: "Member" }),
        h("th", { text: "Accepted points / all accepted / share (open for versions)" }))));
    const body = h("tbody");
    contribution.members.forEach(function (member) {
      const cell = h("td");
      if (contribution.hasAccepted) {
        const details = h("details", null,
          h("summary", null,
            h("strong", { text: fmtNumber(member.points) + " / " + contribution.acceptedPoints +
              " = " + fmtNumber(member.sharePct) + "%" })));
        const list = h("ul", { class: "report-achievements" });
        const items = achievements.get(member.id) || [];
        if (!items.length) list.append(h("li", { text: "No accepted work yet" }));
        items.forEach(function (item) {
          list.append(h("li", {
            text: item.title + " (v" + item.version + ": " + item.taskPoints + " points × " +
              fmtNumber(item.sharePct) + "% = " + fmtNumber(item.memberPoints) + " points)"
          }));
        });
        details.append(list);
        cell.append(details);
      } else {
        cell.textContent = "No accepted contributions yet";
      }
      body.append(h("tr", null, h("td", { text: member.name }), cell));
    });
    table.append(body);
    box.append(h("h3", { text: "Individual contributions (no ranking)" }), table);
    const branchTable = h("table", { class: "report-table" },
      h("thead", null, h("tr", null,
        h("th", { text: "Branch" }), h("th", { text: "Planned" }), h("th", { text: "Accepted" }),
        h("th", { text: "Completion (accepted / planned)" }))));
    const branchBody = h("tbody");
    contribution.branches.forEach(function (branch) {
      branchBody.append(h("tr", null,
        h("td", { text: branch.name }),
        h("td", { text: String(branch.plannedPoints) }),
        h("td", { text: String(branch.acceptedPoints) }),
        h("td", {
          text: branch.hasAccepted
            ? branch.acceptedPoints + " / " + branch.plannedPoints + " = " + fmtNumber(branch.completionPct) + "%"
            : "No accepted contributions yet"
        })));
    });
    branchTable.append(branchBody);
    box.append(h("h3", { text: "Branches" }), branchTable);
    if (data.tasks.length) {
      box.append(h("h3", { text: "Accepted versions (recomputable)" }));
      data.tasks.forEach(function (task) {
        if (!task.score) return;
        const version = task.versions.find(function (v) { return v.version === task.score.version; });
        box.append(h("div", { class: "report-task" },
          h("strong", { text: task.title }),
          h("span", { text: " · Version v" + task.score.version + " · " + task.score.points + " points" }),
          h("p", {
            class: "muted",
            text: version ? version.authors.map(function (a) { return a.name + " " + fmtNumber(a.sharePct) + "%"; }).join(" · ") : ""
          }),
          h("button", {
            class: "link-button", type: "button", text: "Open version",
            onclick: function () { openTask(task.id, task.score.submissionId); }
          })));
      });
    }
    (data.disputes || []).forEach(function (report) {
      const facts = report.facts;
      const preview = h("details", { class: "detail-block" }, h("summary", { text: "Dispute #" + report.id + " · " + report.taskTitle }),
        h("p", { text: "Assignee at filing " + (facts.plan.assignee || "Record unavailable") + " · " +
          (facts.plan.authors.map(function (author) { return author.name + " " + author.sharePct + "%"; }).join(", ") || "Author details unavailable")
          + " · Deadline " + fmtDateTime(facts.plan.currentDeadlineAt) }));
      facts.reassignments.slice().reverse().forEach(function (change) {
        preview.append(h("p", { text: "Handoff " + fmtDateTime(change.createdAt) + " · " + change.fromName + " → " + change.toName + " · " + change.reason }));
      });
      facts.dateChanges.slice().reverse().forEach(function (change) {
        preview.append(h("p", { text: "Extension " + fmtDateTime(change.decidedAt || change.createdAt) + " · " +
          fmtDateTime(change.oldDeadline) + " → " + fmtDateTime(change.newDeadline) + " · " + change.reason }));
      });
      box.append(preview);
    });
    box.append(h("p", { class: "muted-note", text: "The HTML export contains a JSON appendix with task points and frozen shares only. It never contains votes, vote counts, or voting times." }));
  }

  function openDialog(id) {
    const dialog = $(id);
    if (dialog && !dialog.open) dialog.showModal();
  }

  function closeDialog(id) {
    const dialog = $(id);
    if (dialog && dialog.open) dialog.close();
  }

  function showJoinDialog(code) {
    $("join-project-form").reset();
    const input = $("join-project-form").elements.inviteCode;
    input.value = code || "";
    $("join-preview").textContent = code
      ? "Looking up this invite code…"
      : "Enter a code to see which group it belongs to.";
    openDialog("join-dialog");
    if (code) scheduleInvitePreview(code, 0);
    else requestAnimationFrame(function () { input.focus(); });
  }

  let inviteTimer = null;
  function scheduleInvitePreview(code, delay) {
    clearTimeout(inviteTimer);
    const value = String(code || "").trim();
    if (value.length < 4) {
      $("join-preview").textContent = "Enter a code to see which group it belongs to.";
      return;
    }
    inviteTimer = setTimeout(function () { previewInvite(value); }, delay === undefined ? 220 : delay);
  }

  async function previewInvite(code) {
    const label = $("join-preview");
    try {
      const result = await api("/api/invites/" + encodeURIComponent(code.trim().toUpperCase()));
      const project = result.project;
      label.textContent = "Invite code matches “" + project.name + "” · " + project.memberCount +
        " members · " + project.plannedPoints + " planned points" +
        (result.alreadyMember ? " · you are already a member" : "");
      label.classList.toggle("is-member", Boolean(result.alreadyMember));
    } catch (error) {
      label.textContent = error.message;
      label.classList.remove("is-member");
    }
  }

  let assistantCloseTimer = null;
  function openAssistant() {
    if ($("assistant-button").disabled) return;
    clearTimeout(assistantCloseTimer);
    const panel = $("assistant-panel");
    const backdrop = $("assistant-backdrop");
    panel.hidden = false;
    backdrop.hidden = false;
    requestAnimationFrame(function () {
      panel.classList.add("is-open");
      backdrop.classList.add("is-open");
    });
    $("assistant-button").setAttribute("aria-expanded", "true");
    $("ai-question").focus();
  }

  function closeAssistant(immediate) {
    const panel = $("assistant-panel");
    const backdrop = $("assistant-backdrop");
    if (panel.hidden) return;
    panel.classList.remove("is-open");
    backdrop.classList.remove("is-open");
    $("assistant-button").setAttribute("aria-expanded", "false");
    const finish = function () {
      panel.hidden = true;
      backdrop.hidden = true;
    };
    clearTimeout(assistantCloseTimer);
    if (immediate) finish();
    else assistantCloseTimer = setTimeout(finish, 180);
    $("assistant-button").focus();
  }

  function setWorkspaceVisible(visible) {
    $("empty-state").hidden = visible;
    $("project-stats").hidden = !visible;
    document.querySelector(".notice-board").hidden = !visible;
    document.querySelector(".work-grid").hidden = !visible;
    document.querySelector(".tools-grid").hidden = !visible;
    $("assistant-button").disabled = !visible;
    $("settings-button").disabled = !visible;
    $("add-task-button").disabled = !visible;
  }

  window.addEventListener("DOMContentLoaded", boot);
  window.afState = state;

  /* ------------------------------------------------------------------ */
  /* Task dialog                                                         */
  /* ------------------------------------------------------------------ */

  async function openTask(taskId, submissionId) {
    if (!state.projectId) return;
    try {
      const projectId = state.projectId;
      state.openTaskId = taskId;
      const task = await api("/api/projects/" + projectId + "/tasks/" + taskId);
      if (state.projectId !== projectId || state.openTaskId !== taskId) return;
      const selected = task.submissions.find(function (version) { return version.id === submissionId; }) || task.submissions[0];
      setEvidenceContext(task, selected);
      renderTaskDialog(task, submissionId || null);
      updateUrl({ project: state.projectId, task: taskId, submission: submissionId || null });
      openDialog("task-dialog");
      if (submissionId) {
        requestAnimationFrame(function () {
          const node = document.getElementById("submission-" + submissionId);
          if (node) {
            node.classList.add("highlight");
            node.scrollIntoView({ block: "center", behavior: "smooth" });
          }
        });
      }
    } catch (error) {
      toast(error.message, "error");
    }
  }

  function setEvidenceContext(task, submission) {
    state.evidenceContext = {
      taskId: task.id,
      submissionId: submission?.id || 0,
      title: task.title + (submission ? " · v" + submission.version : "")
    };
    updateEvidenceContext();
  }

  function stateLabelForSubmission(status) {
    return STATUS_LABELS[status] || status;
  }

  function submissionStatusClass(status) {
    if (status === "accepted") return "done";
    if (status === "rejected" || status === "superseded" || status === "withdrawn") return "blocked";
    return "working";
  }

  function renderTaskDialog(task, highlightSubmission) {
    const root = $("task-dialog-content");
    clear(root);

    const privateEvent = !task.accepted
      ? (task.events || []).slice().reverse().find(function (e) { return e.type === "reminder_private"; })
      : null;
    if (privateEvent) {
      root.append(h("div", { class: "private-reminder dialog-reminder" },
        h("strong", { text: "Private checkpoint reminder — visible only to the assignee" }),
        h("span", { text: " " + privateEvent.body + " (" + fmtDateTime(privateEvent.createdAt) + ")" })));
    }

    const header = h("div", { class: "task-dialog-title" },
      h("div", null,
        h("p", { class: "eyebrow", text: task.branchName + " · " + task.points + " points" }),
        h("h2", { text: task.title }),
        h("p", { class: "muted", text: "Assignee " + task.assigneeName })),
      statusPill(task.state));
    root.append(header);

    if (task.followUpData) {
      root.append(h("div", { class: "banner warning" },
        "Group follow-up flag since " + fmtDateTime(task.followUpData.at) +
        " — no recorded response 24 hours after the private reminder."));
    }

    const agreement = h("section", { class: "detail-step" },
      detailStepHead("01", "Current agreement",
        "Each version retains its original criteria and authors."));
    const meta = h("div", { class: "task-meta-grid" });
    meta.append(metaCell("Checkpoint", fmtDateTime(task.checkpointAt)));
    meta.append(metaCell("Deadline", fmtDateTime(task.deadlineAt)));
    meta.append(metaCell("Planned points", String(task.points)));
    meta.append(metaCell("Branch", task.branchName));
    agreement.append(meta);
    const authorBox = h("div", { class: "author-list" });
    authorBox.append(h("span", { class: "meta-label", text: "Authors and shares for the next submission" }));
    task.planAuthors.forEach(function (author) {
      authorBox.append(h("span", { class: "author-chip", text: author.name + " · " + fmtNumber(author.sharePct) + "%" }));
    });
    agreement.append(authorBox);
    const descriptionGrid = h("div", { class: "detail-grid two-up" });
    descriptionGrid.append(detailBlock("Description", task.description || "—"));
    descriptionGrid.append(detailBlock("Expected output", task.expectedOutput));
    agreement.append(descriptionGrid);

    const submissionStep = h("section", { class: "detail-step" },
      detailStepHead("02", "Submission",
        "A version freezes the deliverable, authors, shares, criteria and eligible voters. Earlier versions remain visible."));
    submissionStep.append(renderSubmissions(task, highlightSubmission));

    const criteriaStep = h("section", { class: "detail-step" },
      detailStepHead("03", "Acceptance criteria",
        "The version freezes this list. The result is decided for the whole version, not by showing who voted or how."));
    criteriaStep.append(renderCriteriaChecklist(task));

    const steps = h("div", { class: "task-steps" }, agreement, submissionStep, criteriaStep);
    root.append(steps);

    const actions = h("section", { class: "task-actions" });
    actions.append(h("h3", { text: "Continue this task" }));

    if (task.canSubmit) {
      actions.append(actionPanel("Submit a new version",
        "Paste the deliverable text or upload a PDF.",
        buildSubmissionForm(task)));
    }

    actions.append(actionPanel("Record a note or response",
      "Use a note to explain progress or respond to a checkpoint reminder. Notes are project-visible; they are not quality evidence for AI answers.",
      buildNoteForm(task)));

    if (task.state !== "accepted") {
      actions.append(actionPanel("Ask for help or record a blocker",
        "An unresolved help request or blocker marks the task as Blocked in the notice board and shows the needed support.",
        buildSupportForm(task)));

      actions.append(actionPanel("Negotiate a deadline",
        "The original deadline is retained. The project owner approves or rejects the new date; both are recorded.",
        buildExtensionForm(task)));

      if (state.board && state.board.project.isOwner) {
        actions.append(actionPanel("Reassign the task",
          "Change assignee only, keep the authors and shares.",
          buildReassignForm(task)));
        actions.append(actionPanel("Edit authors and shares for the next version",
          "Changes apply to the next submission.", buildAuthorsForm(task)));
      }
    }

    actions.append(actionPanel("Open a report from this task",
      "Include a factual reason. A failed acceptance never creates a report automatically. Involved members can respond; uninvolved members review.",
      buildReportForm(task)));

    root.append(actions);
    root.append(renderSupports(task));
    root.append(renderDateChanges(task));
    root.append(renderEvents(task));
    root.append(renderReassignments(task));
    root.append(renderTaskReports(task));
    bindTaskForms(task);
  }

  function detailStepHead(index, title, description) {
    return h("div", { class: "detail-step-head" },
      h("span", { class: "step-index", text: index }),
      h("div", null, h("h3", { text: title }), h("p", { class: "muted-note", text: description })));
  }

  function renderCriteriaChecklist(task) {
    const latest = task.submissions && task.submissions.length ? task.submissions[0] : null;
    const criteria = (latest ? latest.criteriaSnapshot : task.acceptanceCriteria) || "";
    const outcome = latest ? latest.status : "none";
    const outcomeLabel = latest ? stateLabelForSubmission(latest.status) : "No submissions yet";
    const outcomeClass = latest && latest.status === "accepted" ? "done"
      : latest && latest.status === "pending" ? "working" : "blocked";
    const box = h("div", { class: "criteria-review" });
    box.append(h("div", { class: "criteria-outcome" },
      h("span", { class: "meta-label", text: "Version outcome" }),
      h("span", { class: "status-pill " + outcomeClass, text: outcomeLabel }),
      h("span", { class: "muted", text: latest
        ? "Version v" + latest.version + " · Criteria frozen for this version"
        : "No submissions yet. Showing the original criteria" })));
    const list = h("ol", { class: "criteria-list" });
    const lines = criteria.split(/\r?\n/).map(function (line) {
      return line.trim().replace(/^[-*\d., ) ]+/, "");
    }).filter(Boolean);
    if (!lines.length) {
      list.append(h("li", { text: "No acceptance criteria recorded." }));
    }
    lines.forEach(function (line) {
      list.append(h("li", null,
        h("span", { class: "criterion-text", text: line }),
        h("span", { class: "criterion-source", text: latest ? "v" + latest.version + " frozen" : "original plan" })));
    });
    box.append(list);
    box.append(h("p", { class: "muted-note", text: "An accepted version already scored this task; a rejected version stays in the history and does not score." }));
    return box;
  }

  function metaCell(label, value) {
    return h("div", { class: "meta-cell" },
      h("span", { class: "meta-label", text: label }),
      h("strong", { text: value }));
  }

  function detailBlock(label, value, pre) {
    return h("div", { class: "detail-block" },
      h("span", { class: "meta-label", text: label }),
      pre ? h("pre", { class: "criteria-text", text: value }) : h("p", { text: value }));
  }

  function actionPanel(title, description, form) {
    return h("details", { class: "action-panel" },
      h("summary", { text: title }),
      h("p", { class: "muted-note", text: description }),
      form);
  }

  function labelled(labelText, input) {
    const label = h("label", null, h("span", { text: labelText }), input);
    return label;
  }

  function buildSubmissionForm(task) {
    const form = h("form", { class: "stack-form compact", id: "task-submit-form" });
    form.append(labelled("Delivery summary", h("textarea", { name: "content", rows: "3", maxlength: "8000", placeholder: "What was delivered?" })));
    form.append(labelled("Artifact file name", h("input", { name: "artifactName", type: "text", maxlength: "200", placeholder: "e.g. analysis-report-v2.md" })));
    form.append(labelled("Artifact content (text is frozen into the version)", h("textarea", { name: "artifactText", rows: "6", maxlength: "20000", placeholder: "Paste the deliverable text. Links alone are not frozen evidence." })));
    form.append(h("p", { class: "muted-note", text: "Version " + ((task.submissions && task.submissions[0] ? task.submissions[0].version : 0) + 1) + " will freeze these authors: " + task.planAuthors.map(function (a) { return a.name + " " + fmtNumber(a.sharePct) + "%"; }).join(", ") }));
    const source = h("input", { name: "pdf", type: "file", accept: "application/pdf,.pdf" });
    form.append(labelled("PDF attachment (up to 1 MiB)", source));
    const draft = getSubmissionDraft(task);
    source.addEventListener("change", function () {
      const currentDraft = submissionDrafts.get(task.id);
      if (currentDraft) currentDraft.file = source.files[0] || null;
    });
    if (draft) {
      form.elements.content.value = draft.content;
      form.elements.artifactName.value = draft.artifactName;
      form.elements.artifactText.value = draft.artifactText;
      if (draft.file) {
        const transfer = new DataTransfer();
        transfer.items.add(draft.file);
        source.files = transfer.files;
      }
    }
    form.append(h("p", { role: "status", class: "error-text", id: "submission-error" }));
    const pending = task.submissions.some(function (version) { return version.status === "pending"; });
    if (pending) form.append(h("p", { class: "banner warning", text: "This round is still pending. You can save a draft. An author must withdraw this round before submitting again." }));
    form.append(h("div", { class: "button-row" },
      h("button", { type: "button", class: "outline-button", text: "Save draft", onclick: function () {
        saveSubmissionDraft(task, form);
        toast("Draft saved. You can continue editing after refreshing. Select the attachment again.");
      } }),
      h("button", { type: "submit", text: "Submit new version" })));
    return form;
  }

  const submissionDrafts = new Map();

  function draftKey(task) {
    return "af.submissionDraft." + state.me.id + "." + task.projectId + "." + task.id;
  }

  function getSubmissionDraft(task) {
    if (submissionDrafts.has(task.id)) return submissionDrafts.get(task.id);
    try {
      return JSON.parse(sessionStorage.getItem(draftKey(task)));
    } catch (error) {
      return null;
    }
  }

  function saveSubmissionDraft(task, form) {
    const saved = getSubmissionDraft(task);
    const draft = {
      content: form.elements.content.value,
      artifactName: form.elements.artifactName.value,
      artifactText: form.elements.artifactText.value,
      file: form.elements.pdf.files[0] || saved?.file || null
    };
    submissionDrafts.set(task.id, draft);
    try {
      const { file, ...text } = draft;
      sessionStorage.setItem(draftKey(task), JSON.stringify(text));
    } catch (error) {
      // Keep the in-memory copy when session storage is unavailable.
    }
    return draft;
  }

  async function readPdf(file) {
    if (!file.name.toLowerCase().endsWith(".pdf") || (file.type && file.type !== "application/pdf")) {
      throw new Error("Please select a PDF file.");
    }
    if (file.size > 1048576) throw new Error("The PDF exceeds 1 MiB.");
    return new Promise(function (resolve, reject) {
      const reader = new FileReader();
      reader.onload = function () { resolve(String(reader.result).split(",", 2)[1]); };
      reader.onerror = function () { reject(new Error("Reading PDF failed. Try again.")); };
      reader.readAsDataURL(file);
    });
  }

  function buildAuthorsForm(task) {
    const form = h("form", { class: "stack-form compact", id: "task-authors-form" });
    const previous = task.planAuthors.map(function (author) { return author.name + " " + author.sharePct + "%"; });
    const summary = h("p", { class: "muted-note" });
    const authors = h("div");
    state.board.members.forEach(function (member) {
      const old = task.planAuthors.find(function (entry) { return entry.id === member.id; });
      authors.append(labelled(member.name, h("input", {
        type: "number", min: "0", max: "100", step: "0.01", required: true,
        value: String(old?.sharePct || 0), dataset: { authorId: member.id, name: member.name }
      })));
    });
    function update() {
      const entries = authorChanges(authors);
      summary.textContent = "Previous authors " + previous.join(", ") + " → New authors "
        + (entries.map(function (author) { return author.name + " " + author.sharePct + "%"; }).join(", ") || "None selected")
        + ". Total " + fmtNumber(entries.reduce(function (sum, author) { return sum + author.sharePct; }, 0)) + "%";
    }
    authors.addEventListener("input", update);
    update();
    form.append(authors, summary,
      labelled("Reason for change", h("textarea", { name: "reason", maxlength: "2000", required: true })),
      h("button", { type: "submit", text: "Save authors" }));
    form.addEventListener("submit", function (event) {
      event.preventDefault();
      guard(async function () {
        await taskRequest(task.id, "/authors", { authors: authorChanges(authors), reason: form.elements.reason.value });
        toast("Authors updated.");
      });
    });
    return form;
  }

  function authorChanges(container) {
    return Array.from(container.querySelectorAll("[data-author-id]")).map(function (input) {
      return { userId: Number(input.dataset.authorId), name: input.dataset.name, sharePct: Number(input.value) };
    }).filter(function (author) { return author.sharePct > 0; });
  }

  function buildNoteForm(task) {
    const form = h("form", { class: "stack-form compact", id: "task-note-form" });
    form.append(labelled("Note", h("textarea", { name: "body", rows: "2", maxlength: "2000", placeholder: "What changed, or what is blocking you?" })));
    form.append(h("label", { class: "remember-row" },
      h("input", { type: "checkbox", name: "ack" }),
      h("span", { text: "Also acknowledge the private checkpoint reminder" })));
    form.append(h("button", { type: "submit", text: "Add note" }));
    return form;
  }

  function buildSupportForm(task) {
    const form = h("form", { class: "stack-form compact", id: "task-support-form" });
    form.append(labelled("Type", h("select", { name: "kind" },
      h("option", { value: "help", text: "Ask the group for help" }),
      h("option", { value: "blocker", text: "Record a blocker" }))));
    form.append(labelled("What is happening?", h("textarea", { name: "body", rows: "2", maxlength: "2000", required: true })));
    form.append(labelled("What support is needed?", h("textarea", { name: "neededSupport", rows: "2", maxlength: "1000", required: true, placeholder: "Specific dependency or decision needed" })));
    form.append(h("button", { type: "submit", text: "Record and show in board" }));
    return form;
  }

  function buildExtensionForm(task) {
    const form = h("form", { class: "stack-form compact", id: "task-extension-form" });
    form.append(labelled("Proposed new deadline", h("input", { name: "newDeadline", type: "datetime-local", required: true })));
    form.append(labelled("Reason", h("textarea", { name: "reason", rows: "2", maxlength: "2000", required: true })));
    form.append(h("button", { type: "submit", text: "Request extension" }));
    return form;
  }

  function buildReassignForm(task) {
    const form = h("form", { class: "stack-form compact", id: "task-reassign-form" });
    const select = h("select", { name: "assigneeId", required: true });
    state.board.members.forEach(function (member) {
      if (member.id !== task.assigneeId) select.append(h("option", { value: String(member.id), text: member.name }));
    });
    form.append(labelled("New assignee", select));
    form.append(labelled("Reason", h("textarea", { name: "reason", rows: "2", maxlength: "2000", required: true })));
    form.append(h("button", { type: "submit", text: "Reassign and keep history" }));
    return form;
  }

  function buildReportForm(task) {
    const form = h("form", { class: "stack-form compact", id: "task-report-form" });
    form.append(labelled("Reason and facts", h("textarea", { name: "reason", rows: "3", maxlength: "4000", required: true, placeholder: "Link the original plan, dates, reminders, and delivery record." })));
    form.append(h("button", { type: "submit", text: "Create report" }));
    return form;
  }

  function renderSupports(task) {
    const box = h("section", { class: "task-section" }, h("h3", { text: "Help and blockers" }));
    if (!task.supports || !task.supports.length) {
      box.append(h("p", { class: "empty-state", text: "No help or blocker records yet." }));
      return box;
    }
    task.supports.forEach(function (support) {
      const item = h("div", { class: "support-item" },
        h("div", { class: "member-line" },
          h("strong", { text: support.kind === "help" ? "Help requested" : "Blocker" }),
          h("span", { class: "muted", text: " · " + support.userName + " · " + fmtDateTime(support.createdAt) }),
          support.resolvedAt ? h("span", { class: "status-pill done", text: "Resolved" }) : h("span", { class: "status-pill blocked", text: "Unresolved" })),
        h("p", { text: support.body }),
        h("p", { class: "muted", text: "Needed: " + support.neededSupport }));
      if (!support.resolvedAt) {
        item.append(h("button", {
          class: "outline-button small", type: "button", text: "Mark support resolved",
          onclick: function () {
            guard(async function () {
              await taskRequest(task.id, "/support/" + support.id + "/resolve", {});
              toast("Support resolved.");
            });
          }
        }));
      }
      box.append(item);
    });
    return box;
  }

  function submissionPreviewSlot(submission) {
    return h("figure", {
      class: "image-slot image-slot-b",
      "data-slot": "submission-preview",
      "aria-label": "Reserved submission preview image slot, 4 by 3"
    },
      h("div", { class: "slot-placeholder" },
        h("span", { class: "slot-purpose", text: "submission-preview" }),
        h("strong", { class: "slot-doc", text: submission.artifactName || "Untitled text submission" }),
        h("span", { class: "slot-meta", text: "v" + submission.version + " · No preview available · 4:3 · 1200 × 900 recommended" })),
      h("figcaption", { text: "No preview image has been provided. The title, version and text submission remain available; this slot is an example attachment and is not used for contribution calculation." }));
  }

  function renderSubmissions(task, highlightSubmission) {
    const box = h("div", { class: "submission-list" });
    box.append(h("p", { class: "muted-note", text: "A version stays Pending review until every frozen author confirms and every frozen voter votes. During voting the group only sees Pending review. Vote choices, counts and vote times are never shown." }));
    if (!task.submissions || !task.submissions.length) {
      box.append(h("p", { class: "empty-state", text: "No submission yet." }));
      return box;
    }
    task.submissions.forEach(function (submission) {
      const actions = submission.actions || {};
      const card = h("article", {
        class: "submission-card" + (submission.id === highlightSubmission ? " highlight" : ""),
        id: "submission-" + submission.id
      });
      const head = h("div", { class: "submission-head" },
        h("div", null,
          h("strong", { text: "Version v" + submission.version }),
          h("small", { text: "submitted " + fmtDateTime(submission.createdAt) + (submission.finalizedAt ? " · finalized " + fmtDateTime(submission.finalizedAt) : "") })),
        h("span", { class: "status-pill " + submissionStatusClass(submission.status), text: stateLabelForSubmission(submission.status) }));
      card.append(head);

      if (submission.withdrawalReason) {
        card.append(h("p", { class: "muted-note", text: "Withdrawal reason: " + submission.withdrawalReason }));
      }
      if (submission.attachmentUrl) {
        card.append(h("a", {
          class: "task-chip", href: submission.attachmentUrl, target: "_blank", rel: "noopener",
          text: "Open PDF · " + submission.artifactName
        }));
      }
      card.append(h("button", {
        type: "button", class: "outline-button small", text: "Ask about this version",
        onclick: function () {
          setEvidenceContext(task, submission);
          closeDialog("task-dialog");
          openAssistant();
        }
      }));

      if (submission.id === task.submissions[0].id) {
        card.append(submissionPreviewSlot(submission));
      }

      const authorLine = h("div", { class: "author-list" });
      authorLine.append(h("span", { class: "meta-label", text: "Frozen authors" }));
      submission.authors.forEach(function (author) {
        authorLine.append(h("span", { class: "author-chip" },
          author.name + " · " + fmtNumber(author.sharePct) + "%" +
          (!actions.allAuthors && submission.status === "pending" ? (author.confirmed ? " · Confirmed" : " · Pending review") : "")));
      });
      card.append(authorLine);

      card.append(h("details", { class: "submission-body", open: submission.id === highlightSubmission },
        h("summary", { text: "Frozen deliverable and criteria" }),
        h("div", { class: "detail-block" }, h("span", { class: "meta-label", text: "Delivery summary" }),
          h("p", { text: submission.content || "—" })),
        submission.artifactText ? h("div", { class: "detail-block" },
          h("span", { class: "meta-label", text: submission.artifactName || "Deliverable text" }),
          h("pre", { class: "artifact-text", text: submission.artifactText })) : null,
        h("div", { class: "detail-block" }, h("span", { class: "meta-label", text: "Acceptance criteria at submission time" }),
          h("pre", { class: "criteria-text", text: submission.criteriaSnapshot }))));

      if (submission.status === "pending") {
        if (actions.canWithdraw) {
          const withdraw = h("form", { class: "stack-form compact" },
            labelled("Withdrawal reason", h("textarea", { name: "reason", required: true, maxlength: "2000", rows: "2" })),
            h("button", { class: "outline-button", type: "submit", text: "Withdraw review round" }));
          withdraw.addEventListener("submit", function (event) {
            event.preventDefault();
            guard(async function () {
              const draft = $("task-submit-form");
              if (draft) saveSubmissionDraft(task, draft);
              await taskRequest(task.id, "/submissions/" + submission.id + "/withdraw", {
                reason: withdraw.elements.reason.value
              });
              toast("Review round withdrawn.");
            });
          });
          card.append(withdraw);
        }
        if (actions.canConfirm) {
          card.append(h("button", {
            class: "primary-small", type: "button", text: "I confirm this submission as a co-author",
            onclick: function () {
              guard(async function () {
                const updated = await api("/api/projects/" + state.projectId + "/tasks/" + task.id +
                  "/submissions/" + submission.id + "/confirm", { method: "POST", body: {} });
                await refreshTaskAfterAction(task.id, updated);
                toast("Author confirmation recorded.");
              });
            }
          }));
        }
        if (actions.canVote) {
          if (actions.allAuthors) {
            card.append(h("p", { class: "muted-note", text: "Every project member is a co-author of this version, so all members vote separately and anonymously." }));
          } else {
            card.append(h("p", { class: "muted-note", text: "Co-authors sign off above. Every other frozen member votes once and anonymously below." }));
          }
          if (actions.inferenceWarning) {
            card.append(h("div", { class: "banner warning", text: actions.inferenceWarning }));
          }
          card.append(h("div", { class: "button-row vote-row" },
            h("button", {
              class: "vote-button", type: "button", text: "Agree anonymously",
              onclick: function () {
                castVote(task.id, submission.id, "agree", actions);
              }
            }),
            h("button", {
              class: "vote-button", type: "button", text: "Disagree anonymously",
              onclick: function () {
                castVote(task.id, submission.id, "disagree", actions);
              }
            })));
        } else {
          card.append(h("p", { class: "muted-note", text: "This version is still Pending review. Waiting for frozen participants; no counts or choices are displayed." }));
        }
      }
      box.append(card);
    });
    return box;
  }

  function castVote(taskId, submissionId, choice, actions) {
    const warning = actions && actions.inferenceWarning
      ? "This is a two-person group; the submitter may infer your choice. "
      : "";
    if (!window.confirm(warning + "Cast your anonymous " + (choice === "agree" ? "agree" : "disagree") + " vote? It cannot be changed.")) return;
    guard(async function () {
      const updated = await api("/api/projects/" + state.projectId + "/tasks/" + taskId +
        "/submissions/" + submissionId + "/vote", { method: "POST", body: { choice: choice } });
      await refreshTaskAfterAction(taskId, updated);
      toast("Vote recorded anonymously.");
    });
  }

  function renderDateChanges(task) {
    const box = h("section", { class: "task-section" }, h("h3", { text: "Deadline negotiations" }));
    if (!task.dateChanges || !task.dateChanges.length) {
      box.append(h("p", { class: "empty-state", text: "No deadline changes recorded." }));
      return box;
    }
    task.dateChanges.forEach(function (change) {
      const item = h("div", { class: "support-item", id: "date-change-" + change.id },
        h("div", { class: "member-line" },
          h("strong", { text: STATUS_LABELS[change.status] || change.status }),
          h("span", { class: "muted", text: " · requested by " + change.requester + " · " + fmtDateTime(change.createdAt) })),
        h("p", { text: "Original deadline " + fmtDateTime(change.oldDeadline) + " → proposed " + fmtDateTime(change.newDeadline) }),
        h("p", { text: "Reason: " + change.reason }),
        change.decidedAt ? h("p", { class: "muted", text: "Decided by " + (change.decider || "owner") + " at " + fmtDateTime(change.decidedAt) + (change.decisionNote ? " · " + change.decisionNote : "") }) : null);
      if (change.status === "pending" && state.board.project.isOwner) {
        item.append(h("div", { class: "button-row" },
          h("button", {
            class: "primary-small", type: "button", text: "Approve new deadline",
            onclick: function () {
              guard(async function () {
                const updated = await api("/api/projects/" + state.projectId + "/tasks/" + task.id +
                  "/extension/" + change.id + "/decide", { method: "POST", body: { approve: true, decisionNote: "Approved in the workspace." } });
                await refreshTaskAfterAction(task.id, updated);
                toast("Extension approved; original deadline kept in history.");
              });
            }
          }),
          h("button", {
            class: "outline-button small danger", type: "button", text: "Reject",
            onclick: function () {
              guard(async function () {
                const updated = await api("/api/projects/" + state.projectId + "/tasks/" + task.id +
                  "/extension/" + change.id + "/decide", { method: "POST", body: { approve: false, decisionNote: "Keep the original deadline." } });
                await refreshTaskAfterAction(task.id, updated);
                toast("Extension rejected; original deadline retained.");
              });
            }
          })));
      }
      box.append(item);
    });
    return box;
  }

  function renderEvents(task) {
    const box = h("section", { class: "task-section" }, h("h3", { text: "Recorded timeline" }));
    if (!task.events || !task.events.length) {
      box.append(h("p", { class: "empty-state", text: "No events yet." }));
      return box;
    }
    const list = h("div", { class: "event-list" });
    task.events.forEach(function (event) {
      list.append(h("div", { class: "event-row" + (event.visibility === "private" ? " private" : "") },
        h("span", { class: "event-time", text: fmtDateTime(event.createdAt) }),
        h("div", null,
          h("strong", { text: event.actorName }),
          h("p", { text: event.body }),
          event.visibility === "private" ? h("small", { class: "muted", text: "Private to the assignee" }) : null)));
    });
    box.append(list);
    return box;
  }

  function renderReassignments(task) {
    if (!task.reassignments || !task.reassignments.length) return h("span");
    const box = h("section", { class: "task-section" }, h("h3", { text: "Reassignment history" }));
    task.reassignments.forEach(function (item) {
      const original = (item.originalPlanAuthors || []).map(function (author) {
        return author.name + " " + fmtNumber(author.sharePct) + "%";
      }).join(" · ");
      box.append(h("div", { class: "support-item" },
        h("p", { text: item.fromName + " → " + item.toName + " · " + fmtDateTime(item.createdAt) }),
        h("p", { class: "muted", text: "Reason: " + item.reason }),
        h("p", { class: "muted", text: "Original assignment retained: " + (original || "see history") })));
    });
    return box;
  }

  function renderTaskReports(task) {
    const box = h("section", { class: "task-section" }, h("h3", { text: "Reports from this task" }));
    if (!task.reports || !task.reports.length) {
      box.append(h("p", { class: "empty-state", text: "No report has been opened from this task." }));
      return box;
    }
    task.reports.forEach(function (report) {
      box.append(h("button", {
        class: "compact-item clickable", type: "button", onclick: function () { openReport(report.id); }
      }, h("span", { class: "mini-avatar violet", text: "!" }),
        h("div", null, h("strong", { text: "Dispute #" + report.id + " · " + (STATUS_LABELS[report.status] || report.status) }),
          h("small", { text: report.reason }))));
    });
    return box;
  }

  function bindTaskForms(task) {
    const submit = $("task-submit-form");
    if (submit) {
      submit.addEventListener("submit", async function (event) {
        event.preventDefault();
        const button = event.submitter;
        const errorText = submit.querySelector("#submission-error");
        errorText.textContent = "";
        if (button) button.disabled = true;
        try {
          const projectId = state.projectId;
          const draft = saveSubmissionDraft(task, submit);
          const data = { content: draft.content, artifactName: draft.artifactName, artifactText: draft.artifactText };
          if (draft.file) {
            data.artifactName = draft.file.name;
            data.pdfBase64 = await readPdf(draft.file);
          }
          const updated = await api("/api/projects/" + projectId + "/tasks/" + task.id + "/submissions",
            { method: "POST", body: data });
          submissionDrafts.delete(task.id);
          try { sessionStorage.removeItem(draftKey(task)); } catch (error) { /* Storage can be unavailable. */ }
          if (state.projectId !== projectId) return;
          await refreshTaskAfterAction(task.id, updated);
          toast("New version submitted; authors and criteria are frozen.");
        } catch (error) {
          errorText.textContent = error.message;
        } finally {
          if (button) button.disabled = false;
        }
      });
    }
    const note = $("task-note-form");
    if (note) {
      note.addEventListener("submit", function (event) {
        event.preventDefault();
        guard(async function () {
          const form = new FormData(note);
          const updated = await api("/api/projects/" + state.projectId + "/tasks/" + task.id + "/note",
            { method: "POST", body: { body: form.get("body"), ack: form.get("ack") === "on" } });
          await refreshTaskAfterAction(task.id, updated);
          toast("Note recorded.");
        });
      });
    }
    const support = $("task-support-form");
    if (support) {
      support.addEventListener("submit", function (event) {
        event.preventDefault();
        guard(async function () {
          const data = Object.fromEntries(new FormData(support).entries());
          const updated = await api("/api/projects/" + state.projectId + "/tasks/" + task.id + "/support",
            { method: "POST", body: data });
          await refreshTaskAfterAction(task.id, updated);
          toast("Support need is now visible on the board.");
        });
      });
    }
    const extension = $("task-extension-form");
    if (extension) {
      extension.elements.newDeadline.value = toLocalInput(task.deadlineAt);
      extension.addEventListener("submit", function (event) {
        event.preventDefault();
        guard(async function () {
          const data = Object.fromEntries(new FormData(extension).entries());
          const updated = await api("/api/projects/" + state.projectId + "/tasks/" + task.id + "/extension",
            { method: "POST", body: data });
          await refreshTaskAfterAction(task.id, updated);
          toast("Extension request recorded; the owner decides.");
        });
      });
    }
    const reassign = $("task-reassign-form");
    if (reassign) {
      reassign.addEventListener("submit", function (event) {
        event.preventDefault();
        guard(async function () {
          const data = Object.fromEntries(new FormData(reassign).entries());
          data.assigneeId = Number(data.assigneeId);
          const updated = await api("/api/projects/" + state.projectId + "/tasks/" + task.id + "/reassign",
            { method: "POST", body: data });
          await refreshTaskAfterAction(task.id, updated);
          toast("Task reassigned; the original plan is retained in history.");
        });
      });
    }
    const report = $("task-report-form");
    if (report) {
      report.addEventListener("submit", function (event) {
        event.preventDefault();
        guard(async function () {
          const data = Object.fromEntries(new FormData(report).entries());
          const detail = await api("/api/projects/" + state.projectId + "/reports",
            { method: "POST", body: { taskId: task.id, reason: data.reason } });
          await loadBoard();
          renderReportDialog(detail);
          openDialog("report-dialog");
          toast("Report created. Involved members can respond; uninvolved members can review.");
        });
      });
    }
  }

  async function taskRequest(taskId, suffix, body) {
    const updated = await api("/api/projects/" + state.projectId + "/tasks/" + taskId + suffix,
      { method: "POST", body: body || {} });
    await refreshTaskAfterAction(taskId, updated);
    return updated;
  }

  async function refreshTaskAfterAction(taskId, updated) {
    await loadBoard();
    if (state.openTaskId === taskId) {
      // Always refetch after the board passes through the reminder evaluator:
      // a note may have just resolved a 24-hour follow-up flag.
      const fresh = await api("/api/projects/" + state.projectId + "/tasks/" + taskId);
      renderTaskDialog(fresh, updated && updated.submissions && updated.submissions[0]
        ? updated.submissions[0].id : null);
    }
  }

  /* ------------------------------------------------------------------ */
  /* Report dialog                                                       */
  /* ------------------------------------------------------------------ */

  async function openReport(reportId) {
    try {
      const detail = await api("/api/projects/" + state.projectId + "/reports/" + reportId);
      renderReportDialog(detail);
      openDialog("report-dialog");
    } catch (error) {
      toast(error.message, "error");
    }
  }

  function renderReportDialog(report) {
    const root = $("report-dialog-content");
    clear(root);
    root.append(h("div", { class: "task-dialog-title" },
      h("div", null,
        h("p", { class: "eyebrow", text: "Report #" + report.id + " · " + report.taskTitle }),
        h("h2", { text: STATUS_LABELS[report.status] || report.status }),
        h("p", { class: "muted", text: "By " + report.reporter + " · " + fmtDateTime(report.createdAt) })),
      h("span", { class: "status-pill " + (report.status === "open" ? "blocked" : "done"),
        text: STATUS_LABELS[report.status] || report.status })));

    root.append(h("div", { class: "detail-block" },
      h("span", { class: "meta-label", text: "Reported reason" }),
      h("p", { text: report.reason })));

    const facts = report.facts;
    if (facts) {
      root.append(h("section", { class: "task-section" },
        h("h3", { text: "Recorded facts" }),
        h("p", { text: "Agreement at filing · Assignee " + (facts.plan.assignee || "Record unavailable") + " · " + facts.plan.points + " points" }),
        h("p", { class: "muted", text: "Agreed checkpoint " + fmtDateTime(facts.plan.checkpointAt) + " · Deadline " + (facts.plan.currentDeadlineAt ? fmtDateTime(facts.plan.currentDeadlineAt) : "Record unavailable") }),
        h("p", { class: "muted", text: "Authors at filing " + (facts.plan.authors.map(function (a) { return a.name + " " + fmtNumber(a.sharePct) + "%"; }).join(" · ") || "Record unavailable") }),
        facts.plan.historyNote ? h("p", { class: "muted-note", text: facts.plan.historyNote }) : null,
        (facts.reassignments || []).slice().reverse().map(function (change) {
          return h("p", { text: "Handoff " + fmtDateTime(change.createdAt) + " · " + change.fromName + " → " + change.toName + " · " + change.reason });
        }),
        (facts.dateChanges || []).slice().reverse().map(function (change) {
          return h("p", { text: "Extension " + fmtDateTime(change.decidedAt || change.createdAt) + " · "
            + fmtDateTime(change.oldDeadline) + " → " + fmtDateTime(change.newDeadline)
            + " · " + stateLabelForSubmission(change.status) + " · " + change.reason });
        }),
        h("p", { class: "muted", text: "Versions: " + (facts.versions.length ? facts.versions.map(function (v) {
          return "v" + v.version + " " + stateLabelForSubmission(v.status)
            + (v.withdrawalReason ? " · " + fmtDateTime(v.finalizedAt) + " · " + v.withdrawalReason : "");
        }).join(" · ") : "none") }),
        h("p", { class: "muted", text: "Escalated reminders: " + (facts.reminders.length ? facts.reminders.map(function (r) {
          return fmtDateTime(r.checkpointAt) + " → follow-up " + fmtDateTime(r.groupFollowUpAt);
        }).join(" · ") : "none") })));
    }

    const statements = h("section", { class: "task-section" }, h("h3", { text: "Member statements" }));
    if (!report.statements.length) {
      statements.append(h("p", { class: "empty-state", text: "No statements yet." }));
    }
    report.statements.forEach(function (statement) {
      statements.append(h("div", { class: "statement " + statement.kind },
        h("div", { class: "member-line" },
          h("strong", { text: statement.kind === "response" ? "Responses from involved members" : "Independent reviews" }),
          h("span", { class: "muted", text: " · " + statement.author + " · " + fmtDateTime(statement.createdAt) })),
        h("p", { text: statement.body })));
    });
    root.append(statements);

    if (report.canRespond) {
      root.append(buildStatementForm("respond", "Add a response as an involved member", "Submit response"));
    }
    if (report.canReview) {
      root.append(buildStatementForm("review", "Add a review as an uninvolved member", "Submit review"));
    }
    if (report.canClose) {
      const closeButton = h("button", {
        class: "outline-button", type: "button", text: "Mark report closed",
        onclick: function () {
          guard(async function () {
            const detail = await api("/api/projects/" + state.projectId + "/reports/" + report.id + "/close",
              { method: "POST", body: {} });
            await loadBoard();
            renderReportDialog(detail);
            toast("Report closed.");
          });
        }
      });
      root.append(h("div", { class: "button-row" }, closeButton));
    }
    root.append(h("p", { class: "muted-note", text: "This record never includes vote choices, counts or vote times. Export it from the closing report to hand facts, statements and unresolved disagreements to a teacher." }));
  }

  function buildStatementForm(kind, title, buttonLabel) {
    const form = h("form", { class: "stack-form compact statement-form", dataset: { kind: kind } });
    form.append(h("h3", { text: title }));
    form.append(labelled("Statement", h("textarea", { name: "body", rows: "3", maxlength: "4000", required: true })));
    form.append(h("button", { type: "submit", text: buttonLabel }));
    form.addEventListener("submit", function (event) {
      event.preventDefault();
      const reportId = $("report-dialog-content").querySelector(".task-dialog-title .eyebrow").textContent.match(/#(\d+)/)[1];
      guard(async function () {
        const data = Object.fromEntries(new FormData(form).entries());
        const detail = await api("/api/projects/" + state.projectId + "/reports/" + reportId + "/" + kind,
          { method: "POST", body: data });
        await loadBoard();
        renderReportDialog(detail);
        toast(kind === "respond" ? "Response recorded." : "Review recorded.");
      });
    });
    return form;
  }

  /* ------------------------------------------------------------------ */
  /* Project settings and creation forms                                 */
  /* ------------------------------------------------------------------ */

  async function openSettings() {
    if (!state.board) return;
    const project = state.board.project;
    const root = $("settings-content");
    clear(root);
    root.append(h("div", { class: "settings-section" },
      h("h3", { text: project.name }),
      h("p", { class: "muted", text: project.description || "No description." }),
      h("div", { class: "invite-box" },
        h("span", { class: "meta-label", text: "Invite code" }),
        h("code", { text: project.inviteCode }),
        h("button", {
          class: "outline-button small", type: "button", text: "Copy code",
          onclick: async function () {
            try {
              await navigator.clipboard.writeText(project.inviteCode);
              toast("Invite code copied.");
            } catch (error) {
              toast("Copy failed; select the code manually.", "error");
            }
          }
        }),
        h("button", {
          class: "outline-button small", type: "button", text: "Copy invite link",
          onclick: async function () {
            const link = window.location.origin + "/?invite=" + encodeURIComponent(project.inviteCode);
            try {
              await navigator.clipboard.writeText(link);
              toast("Invite link copied.");
            } catch (error) {
              toast("Copy failed; select the code manually.", "error");
            }
          }
        }))));

    const members = h("div", { class: "settings-section" }, h("h3", { text: "Members" }));
    state.board.members.forEach(function (member) {
      members.append(h("div", { class: "settings-row" },
        h("span", { class: "mini-avatar", text: initials(member.name) }),
        h("strong", { text: member.name }),
        h("span", { class: "muted", text: member.role || "member" }),
        member.id === project.ownerId ? h("span", { class: "status-pill done", text: "owner" }) : null));
    });
    if (!project.isOwner) {
      members.append(h("button", { type: "button", class: "outline-button", text: "Leave project", onclick: function () {
        if (!window.confirm("Leave ‘" + project.name + "’? Historical work and contributions will be retained.")) return;
        guard(async function () {
          await api("/api/projects/" + project.id + "/leave", { method: "POST", body: {} });
          closeDialog("settings-dialog");
          updateUrl({ project: null, task: null, submission: null });
          await refreshProjects();
          toast("You have left the project.");
        });
      } }));
    }
    root.append(members);

    const branches = h("div", { class: "settings-section" }, h("h3", { text: "Branches and planned points" }));
    branches.append(h("p", { text: "Project · " + budgetText(project) }));
    if (project.isOwner) branches.append(planRevision(project, null, openSettings));
    state.board.branches.forEach(function (branch) {
      branches.append(h("div", { class: "settings-row" },
        h("strong", { text: branch.name }),
        h("span", { class: "muted", text: budgetText(branch) }),
        h("span", { class: "muted", text: branch.acceptedPoints + " / " + branch.plannedPoints + " = " + fmtNumber(branch.completionPct) + "%" })));
      if (project.isOwner) branches.append(planRevision(project, branch, openSettings));
    });
    branches.append(h("div", { class: "button-row" },
      h("button", {
        class: "outline-button", type: "button", text: "+ Branch",
        onclick: function () {
          $("create-branch-form").reset();
          openDialog("branch-dialog");
        }
      }),
      h("button", {
        class: "outline-button", type: "button", text: "+ Task",
        onclick: function () {
          closeDialog("settings-dialog");
          openTaskCreate();
        }
      })));
    (state.board.planChanges || []).forEach(function (change) {
      branches.append(h("p", { class: "muted-note", text: fmtDateTime(change.createdAt) + " · "
        + (change.branchName || "Project") + " " + change.oldPoints + " → " + change.newPoints + " points · " + change.reason }));
    });
    root.append(branches);

    root.append(h("section", { class: "settings-section" },
      h("h3", { text: "Rules in this build" }),
      h("ul", { class: "rule-list" },
        h("li", { text: "A version freezes deliverable, authors and shares, acceptance criteria, and eligible voters." }),
        h("li", { text: "All author confirmations and all voter ballots must arrive before the result is revealed." }),
        h("li", { text: "A task can be scored at most once, even across retries, refreshes and concurrent requests." }),
        h("li", { text: "Vote choices, counts and times are never shown on the member board, in notifications, in AI answers or in exports." }),
        h("li", { text: "Contribution percentages are computed from accepted task points and frozen shares." }))));
    openDialog("settings-dialog");
  }

  function budgetText(budget) {
    return "Plan " + budget.plannedPoints + " points, allocated " + budget.assignedPoints + " points, remaining "
      + budget.remainingPoints + " points" + (budget.remainingPoints < 0 ? ", Revise planned points" : "");
  }

  function planRevision(project, branch, onSaved) {
    const budget = branch || project;
    const label = branch ? branch.name : "Project";
    const points = h("input", { type: "number", min: "1", max: "10000", step: "1", value: budget.plannedPoints });
    const reason = h("textarea", { rows: "2", maxlength: "2000" });
    const result = h("p", { class: "error-text", role: "status" });
    const fields = h("div", { class: "stack-form compact" },
      labelled("New planned points", points), labelled("Reason for revision", reason), result);
    const save = h("button", { type: "button", text: "Save plan revision", onclick: async function () {
      result.textContent = "";
      if (!points.value || !points.checkValidity() || !reason.value.trim()) {
        result.textContent = "Enter valid points and a reason.";
        return;
      }
      save.disabled = true;
      try {
        const data = { plannedPoints: Number(points.value), reason: reason.value };
        if (branch) data.branchId = branch.id;
        await api("/api/projects/" + project.id + "/plan", { method: "POST", body: data });
        await loadBoard();
        if (project.id === state.projectId) onSaved();
        toast("Planned points updated.");
      } catch (error) {
        result.textContent = error.message;
      } finally {
        save.disabled = false;
      }
    } });
    fields.append(save);
    return h("details", { class: "detail-block" },
      h("summary", { text: "Revise " + label + " budget" }), fields);
  }

  function renderTaskBudget() {
    const panel = $("task-budget");
    const form = $("create-task-form");
    const project = state.board.project;
    const branch = state.board.branches.find(function (item) { return item.id === Number(form.elements.branchId.value); });
    const hint = h("p", { class: "muted-note" });
    function update() {
      const points = Number(form.elements.points.value) || 0;
      const balances = [{ name: "Project", budget: project }];
      if (branch) balances.push({ name: "Branch", budget: branch });
      hint.textContent = balances.map(function (item) {
        const gap = points - item.budget.remainingPoints;
        return item.name + " · " + budgetText(item.budget) + (gap > 0 ? ", over by " + gap + " points" : "");
      }).join("; ");
    }
    panel.replaceChildren(hint);
    if (project.isOwner) {
      panel.append(planRevision(project, null, renderTaskBudget));
      if (branch) panel.append(planRevision(project, branch, renderTaskBudget));
    }
    form.elements.points.oninput = update;
    update();
  }

  function openTaskCreate() {
    if (!state.board) return;
    const form = $("create-task-form");
    form.reset();
    $("task-create-error").textContent = "";
    const branchSelect = $("task-branch");
    clear(branchSelect);
    state.board.branches.forEach(function (branch) {
      branchSelect.append(h("option", { value: String(branch.id), text: branch.name + " (" + branch.plannedPoints + " pts)" }));
    });
    const assigneeSelect = $("task-assignee");
    clear(assigneeSelect);
    state.board.members.forEach(function (member) {
      assigneeSelect.append(h("option", { value: String(member.id), text: member.name + (member.id === state.me.id ? " (you)" : "") }));
    });
    const now = new Date();
    form.elements.checkpointAt.value = toLocalInput(new Date(now.getTime() + 24 * 3600 * 1000).toISOString());
    form.elements.deadlineAt.value = toLocalInput(new Date(now.getTime() + 3 * 24 * 3600 * 1000).toISOString());
    form.elements.points.value = "20";
    buildAuthorRows(Number(assigneeSelect.value));
    assigneeSelect.onchange = function () {
      const checkbox = document.querySelector('#author-rows input[data-user="' + assigneeSelect.value + '"]');
      if (checkbox) {
        checkbox.checked = true;
        distributeShares();
      }
    };
    branchSelect.onchange = renderTaskBudget;
    renderTaskBudget();
    openDialog("task-create-dialog");
  }

  function buildAuthorRows(defaultUserId) {
    const container = $("author-rows");
    clear(container);
    state.board.members.forEach(function (member) {
      const checkbox = h("input", { type: "checkbox", "data-user": String(member.id), checked: member.id === defaultUserId });
      const share = h("input", {
        type: "number", min: "0", max: "100", step: "0.01", "data-share": String(member.id),
        value: member.id === defaultUserId ? "100" : "0", "aria-label": "Share for " + member.name
      });
      checkbox.addEventListener("change", distributeShares);
      share.addEventListener("input", updateShareTotal);
      container.append(h("div", { class: "author-row" },
        h("label", null, checkbox, h("span", { text: member.name })),
        h("span", { class: "muted", text: "share %" }),
        share));
    });
    updateShareTotal();
  }

  function distributeShares() {
    const rows = Array.from(document.querySelectorAll("#author-rows input[type=checkbox]"));
    const checked = rows.filter(function (row) { return row.checked; });
    if (!checked.length) {
      updateShareTotal();
      return;
    }
    const share = Math.floor((10000 / checked.length)) / 100;
    let allocated = 0;
    checked.forEach(function (row, index) {
      const input = document.querySelector('#author-rows input[data-share="' + row.dataset.user + '"]');
      if (index === checked.length - 1) input.value = String(Math.round((100 - allocated) * 100) / 100);
      else {
        input.value = String(share);
        allocated += share;
      }
    });
    rows.filter(function (row) { return !row.checked; }).forEach(function (row) {
      const input = document.querySelector('#author-rows input[data-share="' + row.dataset.user + '"]');
      input.value = "0";
    });
    updateShareTotal();
  }

  function updateShareTotal() {
    const inputs = Array.from(document.querySelectorAll("#author-rows input[data-share]"));
    const total = inputs.reduce(function (sum, input) {
      const checked = document.querySelector('#author-rows input[data-user="' + input.dataset.share + '"]').checked;
      return sum + (checked ? Number(input.value) || 0 : 0);
    }, 0);
    const label = $("share-total");
    if (label) {
      label.textContent = "Total: " + fmtNumber(total) + "%";
      label.className = "share-total " + (Math.abs(total - 100) < 0.01 ? "ok" : "bad");
    }
  }

  async function createTask(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const result = $("task-create-error");
    result.textContent = "";
    try {
      const data = Object.fromEntries(new FormData(form).entries());
      const authors = [];
      document.querySelectorAll("#author-rows input[type=checkbox]").forEach(function (checkbox) {
        if (!checkbox.checked) return;
        const input = document.querySelector('#author-rows input[data-share="' + checkbox.dataset.user + '"]');
        authors.push({ userId: Number(checkbox.dataset.user), sharePct: Number(input.value) });
      });
      const total = authors.reduce(function (sum, a) { return sum + a.sharePct; }, 0);
      if (!authors.length || Math.abs(total - 100) > 0.01) {
        throw new Error("Author shares must add up to 100%.");
      }
      data.branchId = Number(data.branchId);
      data.assigneeId = Number(data.assigneeId);
      data.points = Number(data.points);
      data.authors = authors;
      const created = await api("/api/projects/" + state.projectId + "/tasks", { method: "POST", body: data });
      closeDialog("task-create-dialog");
      await loadBoard();
      await openTask(created.id);
      toast("Task planned; authors, shares, criteria and points are saved.");
    } catch (error) {
      result.textContent = error.message;
    }
  }

  function toLocalInput(iso) {
    if (!iso) return "";
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return "";
    const pad = function (n) { return String(n).padStart(2, "0"); };
    return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate()) +
      "T" + pad(date.getHours()) + ":" + pad(date.getMinutes());
  }

})();
