(function () {
  let notificationStompClient = null;

  function initNotificationWebSocket() {
    if (typeof SockJS === "undefined" || typeof Stomp === "undefined") {
      return;
    }

    const currentMemberId = getCurrentMemberId();
    const isAdmin = isAdminUser();

    // 비로그인 익명 유저인 경우 보호된 웹소켓 연결 시도 안함
    if (!currentMemberId && !isAdmin) {
      return;
    }

    if (notificationStompClient && notificationStompClient.connected) {
      return;
    }

    const socket = new SockJS("/ws");
    notificationStompClient = Stomp.over(socket);
    notificationStompClient.debug = null;

    notificationStompClient.connect({}, () => {
      // 1. 개인 알림 구독 (/topic/notifications/{memberId})
      if (currentMemberId) {
        notificationStompClient.subscribe(`/topic/notifications/${currentMemberId}`, (event) => {
          handleNotificationReceived(event);
        });
      }

      // 2. 재연결 또는 최초 연결 성공 시 DB 미읽음 개수 및 화면 목록 상태 재동기화
      if (typeof window.updateNotificationUnreadCount === "function") {
        window.updateNotificationUnreadCount();
      }
      if (typeof window.loadNotifications === "function") {
        window.loadNotifications();
      }
    }, () => {
      // 연결 오류 시 5초 후 자동 재연결
      setTimeout(initNotificationWebSocket, 5000);
    });
  }

  function handleNotificationReceived(event) {
    try {
      const notification = JSON.parse(event.body);
      if (!notification) return;

      // 1. 토스트 팝업 띄우기
      if (typeof showToast === "function") {
        showToast(notification.title, notification.content, notification.targetUrl, notification.id);
      }

      // 2. 헤더 알림 배지 숫자 +1 동적 증가
      if (typeof updateNotificationUnreadCount === "function") {
        updateNotificationUnreadCount();
      }

      // 3. 현재 알림 목록 화면(/notifications 또는 /admin/notifications)에 DOM 동적 상단 삽입 및 갱신
      appendNotificationToDOM(notification);
    } catch (e) {
      console.error("알림 수신 처리 오류:", e);
    }
  }

  function appendNotificationToDOM(notification) {
    if (!notification) return;

    // 0. 헤더 종 버튼의 최근 알림 목록 (notification-panel.js, 고객 화면)
    if (window.notificationPanel && typeof window.notificationPanel.receive === "function") {
      window.notificationPanel.receive(notification);
    }

    // 1. 고객용 알림 목록 컨테이너 (#notification-container)
    const customerContainer = document.getElementById("notification-container");
    if (customerContainer) {
      if (customerContainer.querySelector("p.text-muted")) {
        customerContainer.innerHTML = "";
      }

      let existingArticle = notification.id ? customerContainer.querySelector(`article[data-id="${notification.id}"]`) : null;
      if (existingArticle) {
        // 기존 묶음 알림 DOM 요소 내용 갱신, 미읽음 클래스/dot 복원 및 최상단 이동
        existingArticle.className = "notification-item is-unread";
        const clusterEl = existingArticle.querySelector(".cluster");
        if (clusterEl && !clusterEl.querySelector(".notification-dot")) {
          const dot = document.createElement("span");
          dot.className = "notification-dot";
          dot.setAttribute("aria-hidden", "true");
          clusterEl.insertBefore(dot, clusterEl.firstChild);
        }
        const titleSpan = existingArticle.querySelector(".badge");
        const contentP = existingArticle.querySelector("p");
        const timeEl = existingArticle.querySelector("time");
        if (titleSpan) titleSpan.textContent = notification.title || "알림";
        if (contentP) contentP.textContent = notification.content || "";
        if (timeEl) timeEl.textContent = "방금 전";
        existingArticle.setAttribute("data-realtime", "true");
        attachCustomerItemEvents(existingArticle, notification);
        customerContainer.insertBefore(existingArticle, customerContainer.firstChild);
      } else {
        const detailBtn = notification.targetUrl
          ? `<a class="btn" data-noti-id="${notification.id || ''}" href="${escapeHtmlNoti(notification.targetUrl)}">상세</a>`
          : "";

        const article = document.createElement("article");
        article.className = "notification-item is-unread";
        article.setAttribute("data-realtime", "true");
        if (notification.id) article.setAttribute("data-id", notification.id);
        article.innerHTML = `
          <div class="cluster" style="align-items:flex-start">
            <span class="notification-dot" aria-hidden="true"></span>
            <div>
              <span class="badge">${escapeHtmlNoti(notification.title || '알림')}</span>
              <p>${escapeHtmlNoti(notification.content || '')}</p>
              <time class="text-muted">방금 전</time>
            </div>
          </div>
          ${detailBtn}
        `;

        attachCustomerItemEvents(article, notification);

        if (customerContainer.firstChild) {
          customerContainer.insertBefore(article, customerContainer.firstChild);
        } else {
          customerContainer.appendChild(article);
        }
      }
      return;
    }

    // 2. 관리자용 알림 목록 컨테이너 (#admin-notification-container)
    const adminContainer = document.getElementById("admin-notification-container");
    if (adminContainer) {
      if (adminContainer.querySelector("td.text-muted")) {
        adminContainer.innerHTML = "";
      }

      let existingRow = notification.id ? adminContainer.querySelector(`tr[data-id="${notification.id}"]`) : null;
      if (existingRow) {
        // 기존 묶음 알림 TR 요소 내용 갱신, 미읽음 클래스/스타일/뱃지 복원 및 최상단 이동
        existingRow.className = "admin-noti-row is-unread";
        existingRow.style.cssText = "font-weight: 600; background-color: rgba(255, 243, 205, 0.2);";
        const cells = existingRow.querySelectorAll("td");
        if (cells.length >= 4) {
          cells[0].textContent = "방금 전";
          const titleBadge = cells[1].querySelector(".badge");
          if (titleBadge) titleBadge.textContent = notification.title || "알림";
          cells[2].textContent = notification.content || "";
          cells[3].innerHTML = '<span class="badge badge--info">미읽음</span>';
        }
        existingRow.setAttribute("data-realtime", "true");
        attachAdminRowEvents(existingRow, notification);
        adminContainer.insertBefore(existingRow, adminContainer.firstChild);
      } else {
        const detailBtn = notification.targetUrl
          ? `<a class="btn btn--sm" data-noti-id="${notification.id || ''}" href="${escapeHtmlNoti(notification.targetUrl)}">상세</a>`
          : "";

        const tr = document.createElement("tr");
        tr.className = "admin-noti-row is-unread";
        tr.setAttribute("data-realtime", "true");
        if (notification.id) tr.setAttribute("data-id", notification.id);
        tr.style.cssText = "font-weight: 600; background-color: rgba(255, 243, 205, 0.2);";
        tr.innerHTML = `
          <td>방금 전</td>
          <td><span class="badge">${escapeHtmlNoti(notification.title || '알림')}</span></td>
          <td>${escapeHtmlNoti(notification.content || '')}</td>
          <td><span class="badge badge--info">미읽음</span></td>
          <td>${detailBtn}</td>
        `;

        attachAdminRowEvents(tr, notification);

        if (adminContainer.firstChild) {
          adminContainer.insertBefore(tr, adminContainer.firstChild);
        } else {
          adminContainer.appendChild(tr);
        }
      }
    }
  }

  function attachCustomerItemEvents(article, notification) {
    if (notification && notification.id) article.setAttribute("data-id", notification.id);
    const detailLink = article.querySelector("a[data-noti-id]");
    if (detailLink && notification && notification.id) detailLink.setAttribute("data-noti-id", notification.id);

    if (article.dataset.hasNotiClick === "true") return;
    article.dataset.hasNotiClick = "true";

    if (detailLink) {
      detailLink.addEventListener("click", function (e) {
        const isUnread = article.classList.contains("is-unread");
        const notiId = this.getAttribute("data-noti-id") || article.getAttribute("data-id");
        if (!isUnread || !notiId) return;

        e.preventDefault();
        const targetHref = this.href;
        markReadApi(notiId).finally(() => {
          location.href = targetHref;
        });
      });
    }

    article.addEventListener("click", function (e) {
      if (e.target.closest("a, button")) return;
      const notiId = article.getAttribute("data-id");
      if (!notiId || !article.classList.contains("is-unread")) return;

      markReadApi(notiId).then(ok => {
        if (ok) {
          article.classList.remove("is-unread");
          const dot = article.querySelector(".notification-dot");
          if (dot) dot.remove();
          if (typeof window.updateNotificationUnreadCount === "function") {
            window.updateNotificationUnreadCount();
          }
        }
      });
    });
  }

  function attachAdminRowEvents(tr, notification) {
    if (notification && notification.id) tr.setAttribute("data-id", notification.id);
    const detailLink = tr.querySelector("a[data-noti-id]");
    if (detailLink && notification && notification.id) detailLink.setAttribute("data-noti-id", notification.id);

    if (tr.dataset.hasNotiClick === "true") return;
    tr.dataset.hasNotiClick = "true";

    if (detailLink) {
      detailLink.addEventListener("click", function (e) {
        const isUnread = tr.classList.contains("is-unread");
        const notiId = this.getAttribute("data-noti-id") || tr.getAttribute("data-id");
        if (!isUnread || !notiId) return;

        e.preventDefault();
        const targetHref = this.href;
        markReadApi(notiId).finally(() => {
          location.href = targetHref;
        });
      });
    }

    tr.addEventListener("click", function (e) {
      if (e.target.closest("a, button")) return;
      const notiId = tr.getAttribute("data-id");
      if (!notiId || !tr.classList.contains("is-unread")) return;

      markReadApi(notiId).then(ok => {
        if (ok) {
          tr.classList.remove("is-unread");
          tr.style.cssText = "";
          const statusTd = tr.children[3];
          if (statusTd) statusTd.innerHTML = '<span class="badge">읽음</span>';
          if (typeof window.updateNotificationUnreadCount === "function") {
            window.updateNotificationUnreadCount();
          }
        }
      });
    });
  }

  function markReadApi(notiId) {
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
    const headers = {};
    if (csrfToken && csrfHeader) headers[csrfHeader] = csrfToken;

    return fetch(`/api/notifications/${notiId}/read`, {
      method: "PATCH",
      headers: headers,
      keepalive: true
    }).then(res => res.ok).catch(() => false);
  }

  function getCurrentMemberId() {
    const metaEl = document.querySelector('meta[name="member-id"]');
    if (metaEl && metaEl.content && metaEl.content !== "null" && metaEl.content.trim() !== "") {
      return metaEl.content.trim();
    }
    const element = document.querySelector("[data-member-id]");
    if (element && element.dataset && element.dataset.memberId && element.dataset.memberId !== "null") {
      return element.dataset.memberId;
    }
    return null;
  }

  function isAdminUser() {
    const identity = document.querySelector("[data-account-identity]");
    return Boolean(identity && identity.textContent && identity.textContent.includes("관리자"));
  }

  function escapeHtmlNoti(text) {
    if (!text) return "";
    return String(text)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  document.addEventListener("DOMContentLoaded", () => {
    initNotificationWebSocket();
  });
})();
