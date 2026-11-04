// 헤더 종 버튼을 누르면 최근 알림을 펼쳐 보여 준다.
// 버튼은 /notifications 링크라 스크립트가 없거나 실패해도 알림 페이지로 이동할 수 있다.
// 실시간 알림(notification-websocket.js)은 window.notificationPanel.receive 로 이 목록 맨 위에 넣는다.
(function () {
  const PANEL_SIZE = 8;

  function escapeHtml(value) {
    if (!value) return "";
    return String(value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  // 화면 이동 주소는 이 사이트 안의 경로만 받는다.
  function safeTargetUrl(value) {
    if (!value || typeof value !== "string") return null;
    return value.startsWith("/") && !value.startsWith("//") ? value : null;
  }

  function csrfHeaders(panel) {
    const token = document.querySelector('meta[name="_csrf"]')?.getAttribute("content") || panel.dataset.csrf;
    const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute("content") || panel.dataset.csrfHeader;
    return token && header ? { [header]: token } : {};
  }

  function formatTime(value) {
    return value ? String(value).replace("T", " ").substring(0, 16) : "";
  }

  function itemHtml(item, timeText) {
    const isRead = item.isRead !== undefined ? item.isRead : item.read;
    const target = safeTargetUrl(item.targetUrl);
    const tag = target ? "a" : "div";
    const href = target ? ` href="${escapeHtml(target)}"` : "";
    return `
      <${tag} class="notification-item notification-panel__item${isRead ? "" : " is-unread"}"
              data-id="${escapeHtml(item.id)}"${href}>
        <div class="cluster">
          ${isRead ? "" : '<span class="notification-dot" aria-hidden="true"></span>'}
          <div>
            <span class="badge">${escapeHtml(item.title || "알림")}</span>
            <p>${escapeHtml(item.content || "")}</p>
            <time class="text-muted">${escapeHtml(timeText)}</time>
          </div>
        </div>
      </${tag}>`;
  }

  function init() {
    const root = document.querySelector("[data-notification-bell]");
    if (!root) return;
    const toggle = root.querySelector("[data-notification-toggle]");
    const panel = root.querySelector("[data-notification-panel]");
    const list = root.querySelector("[data-panel-list]");
    const readAll = root.querySelector("[data-panel-read-all]");
    let loaded = false;
    let loadSeq = 0;

    function message(text) {
      list.innerHTML = `<p class="text-muted text-center">${escapeHtml(text)}</p>`;
    }

    function refreshCount() {
      if (typeof window.updateNotificationUnreadCount === "function") {
        window.updateNotificationUnreadCount();
      }
    }

    function markRead(id) {
      return fetch(`/api/notifications/${encodeURIComponent(id)}/read`, {
        method: "PATCH",
        headers: csrfHeaders(panel),
        keepalive: true
      }).then((response) => response.ok).catch(() => false);
    }

    function markItemRead(item) {
      item.classList.remove("is-unread");
      item.querySelector(".notification-dot")?.remove();
    }

    async function load() {
      const seq = ++loadSeq;
      message("알림을 불러오는 중입니다...");
      try {
        const response = await fetch(`/api/notifications?page=1&size=${PANEL_SIZE}`, {
          headers: { Accept: "application/json" }
        });
        if (seq !== loadSeq) return;
        if (!response.ok) throw new Error("load failed");
        const data = await response.json();
        if (seq !== loadSeq) return;
        if (!Array.isArray(data) || data.length === 0) {
          message("새로운 알림이 없습니다.");
        } else {
          list.innerHTML = data.map((item) => itemHtml(item, formatTime(item.lastEventAt || item.createdAt))).join("");
        }
        loaded = true;
      } catch (error) {
        if (seq === loadSeq) message("알림을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
      }
    }

    function open() {
      panel.hidden = false;
      toggle.setAttribute("aria-expanded", "true");
      root.classList.add("is-open");
      load();
    }

    function close(returnFocus) {
      if (panel.hidden) return;
      panel.hidden = true;
      toggle.setAttribute("aria-expanded", "false");
      root.classList.remove("is-open");
      if (returnFocus) toggle.focus();
    }

    toggle.addEventListener("click", (event) => {
      event.preventDefault();
      if (panel.hidden) open(); else close(false);
    });

    document.addEventListener("click", (event) => {
      if (!root.contains(event.target)) close(false);
    });

    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && !panel.hidden) close(true);
    });

    // 알림을 누르면 읽지 않은 알림만 읽음 처리한 뒤 이동한다. 이미 읽은 알림은 읽은 시각을 덮어쓰지 않는다.
    list.addEventListener("click", (event) => {
      const item = event.target.closest(".notification-panel__item");
      if (!item || !list.contains(item)) return;
      const id = item.dataset.id;
      const unread = item.classList.contains("is-unread");
      const href = item.getAttribute("href");
      if (!unread || !id) return;
      if (href) event.preventDefault();
      markRead(id).then((ok) => {
        if (ok) {
          markItemRead(item);
          refreshCount();
        }
        if (href) location.href = href;
      });
    });

    if (readAll) {
      readAll.addEventListener("click", () => {
        fetch("/api/notifications/read-all", { method: "PATCH", headers: csrfHeaders(panel) })
          .then((response) => {
            if (!response.ok) return;
            list.querySelectorAll(".notification-panel__item.is-unread").forEach(markItemRead);
            refreshCount();
            if (typeof window.loadNotifications === "function") window.loadNotifications();
          })
          .catch(() => {});
      });
    }

    // 실시간 알림: 목록을 이미 불러왔으면 맨 위에 넣고, 같은 알림(묶음 갱신)은 위로 올린다.
    window.notificationPanel = {
      receive(notification) {
        if (!loaded || !notification) return;
        if (list.querySelector("p.text-muted")) list.innerHTML = "";
        const existing = notification.id != null
          ? list.querySelector(`.notification-panel__item[data-id="${CSS.escape(String(notification.id))}"]`)
          : null;
        const holder = document.createElement("div");
        holder.innerHTML = itemHtml({ ...notification, isRead: false }, "방금 전").trim();
        const fresh = holder.firstElementChild;
        if (existing) existing.remove();
        list.insertBefore(fresh, list.firstChild);
        while (list.children.length > PANEL_SIZE) list.lastElementChild.remove();
      }
    };
  }

  document.addEventListener("DOMContentLoaded", init);
})();
