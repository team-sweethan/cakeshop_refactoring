/**
 * 고객용 1:1 동적 채팅 REST API + WebSocket STOMP 연동 스크립트
 */
document.addEventListener("DOMContentLoaded", () => {
  let currentChatRoomId = null;
  let pendingAttachment = null;
  let lastFetchedMessageId = 0;
  let stompClient = null;

  // URL QueryString에서 productId 파라미터 추출
  const urlParams = new URLSearchParams(window.location.search);
  const currentProductId = urlParams.get("productId") ? parseInt(urlParams.get("productId"), 10) : null;

  const chatMessagesContainer = document.getElementById("chatMessages");
  const chatForm = document.getElementById("chatForm");
  const chatInput = document.getElementById("chatInput");
  const chatImageInput = document.getElementById("chatImageInput");
  const imageFileName = document.getElementById("imageFileName");
  const orderSidebarStack = document.querySelector(".chat-sidebar .stack");

  // CSRF 메타 태그 획득 헬퍼
  function getCsrfHeaders() {
    const tokenMeta = document.querySelector('meta[name="_csrf"]');
    const headerMeta = document.querySelector('meta[name="_csrf_header"]');
    const headers = {};
    if (tokenMeta && headerMeta && tokenMeta.content && headerMeta.content) {
      headers[headerMeta.content] = tokenMeta.content;
    }
    return headers;
  }

  // 1. 방 정보 획득 (GET 없으면 404 후 자동으로 POST 생성)
  async function initChatRoom() {
    try {
      let response = await fetch("/api/chat/room");
      if (response.status === 404) {
        // 방이 없으면 POST 생성 요청
        response = await fetch("/api/chat/room", {
          method: "POST",
          headers: getCsrfHeaders()
        });
      }
      if (!response.ok) {
        throw new Error("채팅방을 불러올 수 없습니다.");
      }
      const room = await response.json();
      currentChatRoomId = room.id;

      // 웹소켓 실시간 연결 및 구독을 완료될 때까지 기다린 후 초기 메시지 조회!
      await connectWebSocket(currentChatRoomId);

      // 대화 목록 및 연동 주문 조회
      await loadMessages(currentChatRoomId);
      await loadOrderBanners(currentChatRoomId);
      if (lastFetchedMessageId > 0) {
        sendReadCursor(currentChatRoomId, lastFetchedMessageId);
      }

    } catch (err) {
      console.error(err);
      if (chatMessagesContainer) {
        chatMessagesContainer.innerHTML = `<div class="text-muted" style="text-align:center; padding:20px;">채팅방 연결 중 오류가 발생했습니다.</div>`;
      }
    }
  }

  let reconnectTimer = null;
  let isConnectingWebSocket = false;

  // 웹소켓 STOMP 연결 및 실시간 구독
  function connectWebSocket(roomId) {
    if (stompClient && stompClient.connected) return Promise.resolve();
    if (isConnectingWebSocket) return Promise.resolve();
    if (typeof SockJS === "undefined" || typeof Stomp === "undefined") {
      console.warn("SockJS 또는 Stomp 라이브러리가 로드되지 않았습니다.");
      return Promise.resolve();
    }

    isConnectingWebSocket = true;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }

    return new Promise((resolve) => {
      const socket = new SockJS("/ws");
      stompClient = Stomp.over(socket);
      stompClient.debug = null; // 디버그 콘솔 로그 숨김

      stompClient.connect({}, async () => {
        isConnectingWebSocket = false;

        // A. 대화 메시지 실시간 수신 구독 (/topic/chat/{roomId})
        stompClient.subscribe(`/topic/chat/${roomId}`, (message) => {
          try {
            const msg = JSON.parse(message.body);
            appendIncomingMessage(msg);
          } catch (e) {
            console.error("웹소켓 메시지 파싱 오류:", e);
          }
        });

        // B. 상대방(관리자) 읽음 처리 실시간 수신 구독 (/topic/chat/{roomId}/read)
        stompClient.subscribe(`/topic/chat/${roomId}/read`, (event) => {
          try {
            const readData = JSON.parse(event.body);
            if (readData.readerSide === "ADMIN") {
              markAllMyMessagesRead(readData.lastReadMessageId || readData.lastMessageId);
            }
          } catch (e) {
            console.error("읽음 이벤트 수신 오류:", e);
          }
        });

        // C. 실시간 연동 주문 목록 수신 구독 (/topic/chat/{roomId}/orders)
        stompClient.subscribe(`/topic/chat/${roomId}/orders`, (event) => {
          try {
            orderBannerFetchGen++;
            const orders = JSON.parse(event.body);
            renderOrderSidebar(orders);
          } catch (e) {
            console.error("주문 배너 수신 오류:", e);
          }
        });

        // D. 웹소켓 연결/재연결 완료 시 단절 구간 오프라인 메시지 및 주문 목록 재조회 완료 후 최신 읽음 커서 전파!
        await loadMessages(roomId);
        loadOrderBanners(roomId);
        if (lastFetchedMessageId > 0 && document.visibilityState === "visible") {
          sendReadCursor(roomId, lastFetchedMessageId, true);
        }

        resolve();
      }, (err) => {
        isConnectingWebSocket = false;
        console.error("웹소켓 연결 실시간 에러:", err);
        if (reconnectTimer) clearTimeout(reconnectTimer);
        reconnectTimer = setTimeout(() => {
          if (currentChatRoomId) {
            connectWebSocket(currentChatRoomId);
          }
        }, 5000);
        resolve();
      });
    });
  }

  // 메시지 ID 숫자 순서대로 타임라인에 안전하게 삽입하는 헬퍼
  function insertMessageInOrder(container, msgEl, msgId) {
    if (!container || !msgEl) return;
    if (!msgId) {
      container.appendChild(msgEl);
      return;
    }
    const children = Array.from(container.children);
    for (let i = 0; i < children.length; i++) {
      const child = children[i];
      const idAttr = child.id || "";
      const match = idAttr.match(/(?:admin-)?msg-(\d+)/);
      if (match) {
        const childId = parseInt(match[1], 10);
        if (childId > msgId) {
          container.insertBefore(msgEl, child);
          return;
        }
      }
    }
    container.appendChild(msgEl);
  }

  // 실시간 수신 메시지 DOM 추가
  function appendIncomingMessage(msg) {
    if (!chatMessagesContainer || !msg) return;

    // 중복 추가 방지
    if (msg.id && document.getElementById(`msg-${msg.id}`)) {
      return;
    }

    const emptyNotice = chatMessagesContainer.querySelector(".empty-chat-notice");
    if (emptyNotice) emptyNotice.remove();

    const msgEl = createMessageDOM(msg);
    if (msg.id) msgEl.id = `msg-${msg.id}`;
    insertMessageInOrder(chatMessagesContainer, msgEl, msg.id);

    if (msg.productId) {
      appendProductBannerDOM(msg, chatMessagesContainer, msgEl);
    }
    scrollToBottom();

    if (msg.id) {
      lastFetchedMessageId = Math.max(lastFetchedMessageId, msg.id);
    }

    updateReadBadges(chatMessagesContainer);

    // 상대방(관리자) 메시지 수신 시 즉시 읽음 커서 전송
    if (msg.senderType !== "CUSTOMER" && msg.id) {
      sendReadCursor(currentChatRoomId, msg.id, true);
    }
  }

  // 탭 복귀, 포커스, 클릭 시 읽음 커서 즉시 강제 발송
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && currentChatRoomId && lastFetchedMessageId > 0) {
      sendReadCursor(currentChatRoomId, lastFetchedMessageId, true);
    }
  });
  window.addEventListener("focus", () => {
    if (currentChatRoomId && lastFetchedMessageId > 0) {
      sendReadCursor(currentChatRoomId, lastFetchedMessageId, true);
    }
  });
  document.addEventListener("click", () => {
    if (currentChatRoomId && lastFetchedMessageId > 0) {
      sendReadCursor(currentChatRoomId, lastFetchedMessageId, true);
    }
  });

  // 가장 최근(마지막) 내 메시지에만 읽음/미읽음 배지 표시 헬퍼
  function updateReadBadges(container) {
    if (!container) return;
    const myMessages = container.querySelectorAll(".chat-msg--me");
    myMessages.forEach((msgEl, index) => {
      const badge = msgEl.querySelector(".chat-msg__read");
      if (!badge) return;
      if (index === myMessages.length - 1) {
        badge.style.display = "";
      } else {
        badge.style.display = "none";
      }
    });
  }

  // 상대방이 읽었을 때 내 메시지의 "미읽음" 텍스트를 "읽음"으로 실시간 변경 (lastReadMessageId 이하만 반영)
  function markAllMyMessagesRead(lastReadMessageId) {
    if (!chatMessagesContainer) return;
    const targetReadId = lastReadMessageId ? parseInt(lastReadMessageId, 10) : null;
    const myMessages = chatMessagesContainer.querySelectorAll(".chat-msg--me");
    myMessages.forEach((msgEl) => {
      const badge = msgEl.querySelector(".chat-msg__read");
      if (!badge) return;
      const msgIdAttr = msgEl.getAttribute("id");
      if (msgIdAttr && msgIdAttr.startsWith("msg-")) {
        const msgId = parseInt(msgIdAttr.substring("msg-".length), 10);
        if (!isNaN(msgId) && (targetReadId === null || isNaN(targetReadId) || msgId <= targetReadId)) {
          badge.textContent = "읽음";
        }
      } else {
        badge.textContent = "읽음";
      }
    });
    updateReadBadges(chatMessagesContainer);
  }

  // 2. 대화 메시지 목록 조회 & 타임라인 렌더링
  async function loadMessages(roomId) {
    try {
      const response = await fetch(`/api/chat/messages?chatRoomId=${roomId}&page=1&size=50`);
      if (!response.ok) return;
      const messages = await response.json();
      renderTimeline(messages);
    } catch (err) {
      console.error("메시지 조회 실패:", err);
    }
  }

  // 타임라인 HTML 렌더링 (REST 스냅샷과 실시간 도착 메시지 ID 기준 병합)
  function renderTimeline(messages) {
    if (!chatMessagesContainer) return;

    // REST 조회 중 STOMP로 실시간 먼저 도착했던 신규 메시지 및 상품 배너 DOM 보존
    const existingMsgEls = Array.from(chatMessagesContainer.querySelectorAll("[id^='msg-'], [id^='banner-msg-']"));
    const existingReadSet = new Set();
    existingMsgEls.forEach((el) => {
      const readBadge = el.querySelector(".chat-msg__read");
      if (readBadge && readBadge.textContent.trim() === "읽음") {
        const idStr = el.id.replace("banner-msg-", "").replace("msg-", "");
        existingReadSet.add(idStr);
      }
    });

    chatMessagesContainer.innerHTML = "";

    if (!messages || messages.length === 0) {
      if (existingMsgEls.length === 0) {
        chatMessagesContainer.innerHTML = `<div class="text-muted empty-chat-notice" style="text-align:center; padding:30px;">아직 주고받은 메시지가 없습니다. 문의사항을 남겨보세요!</div>`;
        return;
      }
    }

    const renderedIds = new Set();
    let maxId = lastFetchedMessageId;

    if (messages && messages.length > 0) {
      maxId = Math.max(maxId, messages[messages.length - 1].id || 0);

      messages.forEach((msg) => {
        if (msg.id) renderedIds.add(String(msg.id));
        if (existingReadSet.has(String(msg.id))) {
          msg.isRead = true;
          msg.read = true;
        }
        appendProductBannerDOM(msg, chatMessagesContainer);
        const msgEl = createMessageDOM(msg);
        if (msg.id) msgEl.id = `msg-${msg.id}`;
        chatMessagesContainer.appendChild(msgEl);
      });
    }

    // REST 스냅샷에 포함되지 않았던 실시간 메시지 및 배너 DOM 재첨부 (덮어쓰기 방지)
    existingMsgEls.forEach((el) => {
      const idStr = el.id.replace("banner-msg-", "").replace("msg-", "");
      const parsed = parseInt(idStr, 10);
      if (!isNaN(parsed)) maxId = Math.max(maxId, parsed);
      if (!renderedIds.has(idStr)) {
        chatMessagesContainer.appendChild(el);
      }
    });

    lastFetchedMessageId = maxId;
    updateReadBadges(chatMessagesContainer);
    if (lastFetchedMessageId > 0 && currentChatRoomId) {
      sendReadCursor(currentChatRoomId, lastFetchedMessageId);
    }
    scrollToBottom();
  }

  // 문의 상품 중앙 시스템 배너 카드 생성 헬퍼
  function appendProductBannerDOM(msg, container, targetMsgEl) {
    if (!msg || !msg.productId || !container) return;
    const bannerId = msg.id ? `banner-msg-${msg.id}` : null;
    if (bannerId && document.getElementById(bannerId)) return;

    const pName = msg.productName ? escapeHtml(msg.productName) : `상품 #${msg.productId}`;
    const bannerDiv = document.createElement("div");
    if (bannerId) bannerDiv.id = bannerId;
    bannerDiv.className = "chat-msg chat-msg--system";
    bannerDiv.style.cssText = "margin: 14px 0 8px 0; text-align: center;";
    bannerDiv.innerHTML = `
      <div class="chat-msg__content" style="display: inline-block; background: #fff8eb; border: 1px solid #ffe0b2; border-radius: 20px; padding: 6px 16px; font-size: 12px; color: #e65100; box-shadow: 0 1px 3px rgba(0,0,0,0.05);">
        <strong>[문의 상품]: </strong> <span style="font-weight: 700;">${pName}</span>
      </div>
    `;
    if (targetMsgEl && targetMsgEl.parentNode === container) {
      container.insertBefore(bannerDiv, targetMsgEl);
    } else {
      container.appendChild(bannerDiv);
    }
  }

  // 메시지 단일 DOM 생성
  function createMessageDOM(msg) {
    const isMe = msg.senderType === "CUSTOMER";
    const msgDiv = document.createElement("div");
    msgDiv.className = `chat-msg ${isMe ? "chat-msg--me" : "chat-msg--other"}`;

    const formattedTime = msg.createdAt ? formatTime(msg.createdAt) : "";

    let attachmentsHtml = "";
    if (msg.imageUrls && msg.imageUrls.length > 0) {
      msg.imageUrls.forEach((url) => {
        attachmentsHtml += `<div style="margin-bottom:6px;"><img src="${escapeHtml(url)}" style="max-width:200px; border-radius:8px;" alt="첨부 이미지"/></div>`;
      });
    }

    if (isMe) {
      msgDiv.innerHTML = `
        <div class="chat-msg__body">
          ${attachmentsHtml}
          <div class="chat-msg__content">${escapeHtml(msg.content || "")}</div>
        </div>
        <div class="chat-msg__meta">
          <span class="chat-msg__read">${msg.isRead ? "읽음" : "미읽음"}</span>
          <span class="chat-msg__time">${formattedTime}</span>
        </div>
      `;
    } else {
      msgDiv.innerHTML = `
        <div class="chat-msg__sender">${escapeHtml(msg.senderName || "관리자")}</div>
        <div class="chat-msg--other__content-wrap">
          <div class="chat-msg__body">
            ${attachmentsHtml}
            <div class="chat-msg__content">${escapeHtml(msg.content || "")}</div>
          </div>
          <div class="chat-msg__meta">
            <span class="chat-msg__time">${formattedTime}</span>
          </div>
        </div>
      `;
    }

    return msgDiv;
  }

  let orderBannerFetchGen = 0;

  // 3. 연동 주문 배너 목록 조회 및 동적 헤더 건수 갱신
  async function loadOrderBanners(roomId) {
    if (!orderSidebarStack) return;
    const currentGen = ++orderBannerFetchGen;
    try {
      const response = await fetch(`/api/chat/rooms/${roomId}/orders`);
      if (!response.ok || currentChatRoomId !== roomId) return;
      const orders = await response.json();
      if (currentGen !== orderBannerFetchGen) return;
      renderOrderSidebar(orders);
    } catch (err) {
      console.error("연동 주문 조회 실패:", err);
    }
  }

  function renderOrderSidebar(orders) {
    if (!orderSidebarStack) return;
    orderSidebarStack.innerHTML = "";

    // 주문 개수 헤더 동적 갱신
    const sidebarTitle = document.querySelector(".chat-sidebar h3, .chat-sidebar .sidebar-title, .chat-sidebar strong");
    const count = orders ? orders.length : 0;
    if (sidebarTitle && sidebarTitle.textContent.includes("주문")) {
      sidebarTitle.textContent = `연동 주문 내역 (${count}건)`;
    }

    if (!orders || orders.length === 0) {
      orderSidebarStack.innerHTML = `<p class="text-muted" style="font-size:12px;">연동된 주문 내역이 없습니다.</p>`;
      return;
    }

    orders.forEach((ord) => {
      const itemDiv = document.createElement("div");
      itemDiv.className = "panel chat-order-item";
      itemDiv.style.cssText = "cursor:pointer; padding:10px; margin-bottom:8px;";

      const oNum = ord.orderNumber || ord.orderNo || `주문 #${ord.orderId}`;
      const pName = ord.productName || ord.productSummary || "케이크 주문건";
      const pTime = ord.pickupDateTime || ord.pickupAt || "-";
      const amtStr = (ord.totalAmount !== null && ord.totalAmount !== undefined)
        ? ord.totalAmount.toLocaleString() + "원"
        : "-";

      function formatOrderStatus(status) {
        if (!status) return "접수";
        switch (String(status).toUpperCase()) {
          case "READY_FOR_PICKUP": return "픽업 대기";
          case "IN_PRODUCTION": return "제작 중";
          case "UNDER_REVIEW": return "주문 확인 중";
          case "PENDING_PAYMENT": return "결제 대기";
          case "PICKED_UP": return "픽업 완료";
          case "CANCELED": return "주문 취소";
          case "REJECTED": return "주문 거절";
          case "EXPIRED": return "만료";
          default: return status;
        }
      }

      function formatPickupDateTime(rawTime) {
        return window.CakeShopTime.pickup(rawTime);
      }

      itemDiv.innerHTML = `
        <div class="cluster cluster--between" style="margin-bottom:4px;">
          <strong>${escapeHtml(oNum)}</strong>
          <span class="badge badge--warning">${escapeHtml(formatOrderStatus(ord.orderStatus))}</span>
        </div>
        <p class="text-muted" style="margin:0;">${escapeHtml(pName)}</p>
        <p class="text-muted" style="font-size:11px;">금액: ${amtStr} | 픽업: ${escapeHtml(formatPickupDateTime(pTime))}</p>
        <a class="btn btn--block" href="/orders/${ord.orderId}" style="margin-top:6px;">주문 상세 보기</a>
      `;
      itemDiv.addEventListener("click", (e) => {
        if (e.target.tagName === "A" || e.target.tagName === "BUTTON") return;
        const targetAnchor = document.getElementById(`msg-ord-${ord.orderId}`);
        if (targetAnchor) {
          targetAnchor.scrollIntoView({ behavior: "smooth", block: "center" });
        } else if (chatMessagesContainer) {
          chatMessagesContainer.scrollTop = chatMessagesContainer.scrollHeight;
        }
      });

      orderSidebarStack.appendChild(itemDiv);
    });
  }

  // Enter 키 전송 이벤트 연동 (Shift+Enter는 줄바꿈)
  if (chatInput && chatForm) {
    chatInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        chatForm.requestSubmit();
      }
    });
  }

  let isUploadingAttachment = false;

  // 4. 메시지 전송 (웹소켓 STOMP 발신 + REST Fallback)
  if (chatForm) {
    chatForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      if (!currentChatRoomId) {
        alert("채팅방이 활성화되지 않았습니다.");
        return;
      }

      if (isUploadingAttachment) {
        alert("이미지 업로드 중입니다. 잠시만 기다려주세요.");
        return;
      }

      const contentText = chatInput.value.trim();
      if (!contentText && !pendingAttachment) {
        alert("메시지 내용 또는 이미지를 첨부해주세요.");
        return;
      }

      if (contentText.length > 2000) {
        alert("메시지는 최대 2,000자까지 입력 가능합니다.");
        return;
      }

      const payload = {
        chatRoomId: currentChatRoomId,
        content: contentText,
        productId: currentProductId,
        attachments: pendingAttachment ? [pendingAttachment] : []
      };

      // 웹소켓 연결되어 있으면 STOMP로 실시간 발신 (실패 시 REST Fallback으로 자동 전환하여 drafts 보존)
      if (stompClient && stompClient.connected) {
        try {
          stompClient.send("/app/chat/message", {}, JSON.stringify(payload));
          if (lastFetchedMessageId > 0) {
            sendReadCursor(currentChatRoomId, lastFetchedMessageId);
          }
          chatInput.value = "";
          pendingAttachment = null;
          if (imageFileName) imageFileName.textContent = "선택된 파일 없음";
          if (chatImageInput) chatImageInput.value = "";
          return;
        } catch (stompErr) {
          console.warn("STOMP 메시지 발신 실패, REST Fallback으로 자동 전환합니다:", stompErr);
        }
      }

      // REST Fallback (웹소켓 미연결 시)
      try {
        const response = await fetch("/api/chat/messages", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            ...getCsrfHeaders()
          },
          body: JSON.stringify(payload)
        });

        if (!response.ok) {
          alert("메시지 전송에 실패했습니다.");
          return;
        }

        chatInput.value = "";
        pendingAttachment = null;
        if (imageFileName) imageFileName.textContent = "선택된 파일 없음";
        if (chatImageInput) chatImageInput.value = "";

        await loadMessages(currentChatRoomId);
        if (lastFetchedMessageId > 0) {
          await updateReadCursor(currentChatRoomId, lastFetchedMessageId);
        }

      } catch (err) {
        console.error("메시지 전송 오류:", err);
        alert("메시지 전송 중 오류가 발생했습니다.");
      }
    });
  }

  // 5. 이미지 파일 선택 시 즉시 독립 업로드 실행
  if (chatImageInput) {
    chatImageInput.addEventListener("change", async () => {
      const file = chatImageInput.files[0];
      if (!file) return;

      const currentUploadFile = file;
      isUploadingAttachment = true;
      if (imageFileName) imageFileName.textContent = `⏳ ${file.name} 업로드 중...`;

      const formData = new FormData();
      formData.append("file", file);

      try {
        const response = await fetch("/api/chat/images", {
          method: "POST",
          headers: getCsrfHeaders(),
          body: formData
        });

        if (!response.ok || chatImageInput.files[0] !== currentUploadFile) {
          if (chatImageInput.files[0] === currentUploadFile) {
            alert("이미지 업로드에 실패했습니다. (5MB 이하 이미지 파일만 가능합니다)");
            pendingAttachment = null;
            if (imageFileName) imageFileName.textContent = "선택된 파일 없음";
            if (chatImageInput) chatImageInput.value = "";
          }
          return;
        }

        const objectKey = await response.text();
        if (chatImageInput.files[0] !== currentUploadFile) return;

        pendingAttachment = {
          objectKey: objectKey,
          originalFilename: file.name,
          contentType: file.type || "image/jpeg",
          fileSize: file.size
        };

        if (imageFileName) imageFileName.textContent = `✔ ${file.name} (첨부 준비 완료)`;

      } catch (err) {
        console.error("이미지 업로드 오류:", err);
        if (chatImageInput.files[0] === currentUploadFile) {
          alert("이미지 업로드 처리 중 에러가 발생했습니다.");
          pendingAttachment = null;
          if (imageFileName) imageFileName.textContent = "선택된 파일 없음";
          if (chatImageInput) chatImageInput.value = "";
        }
      } finally {
        if (chatImageInput && chatImageInput.files[0] === currentUploadFile) {
          isUploadingAttachment = false;
        }
      }
    });
  }

  let lastSentReadCursorId = 0;

  // 6. 읽음 커서 갱신 (STOMP 또는 REST API)
  function sendReadCursor(roomId, lastMsgId, force = false) {
    if (!roomId || !lastMsgId || lastMsgId <= 0) return;
    if (document.visibilityState !== "visible") return;
    if (!force && lastMsgId <= lastSentReadCursorId) return;

    lastSentReadCursorId = lastMsgId;
    if (stompClient && stompClient.connected) {
      stompClient.send("/app/chat/read", {}, JSON.stringify({
        chatRoomId: roomId,
        lastReadMessageId: lastMsgId
      }));
    } else {
      updateReadCursor(roomId, lastMsgId);
    }
  }

  async function updateReadCursor(roomId, lastMsgId) {
    if (!roomId || !lastMsgId) return;
    try {
      await fetch(`/api/chat/read-cursor?chatRoomId=${roomId}&lastReadMessageId=${lastMsgId}`, {
        method: "PATCH",
        headers: getCsrfHeaders()
      });
    } catch (err) {
      console.error("읽음 커서 갱신 실패:", err);
    }
  }

  // 스크롤 맨 아래로 이동
  function scrollToBottom() {
    if (chatMessagesContainer) {
      chatMessagesContainer.scrollTop = chatMessagesContainer.scrollHeight;
    }
  }

  // 헬퍼 함수
  function escapeHtml(str) {
    if (!str) return "";
    return String(str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  function formatTime(isoString) {
    return window.CakeShopTime.time(isoString);
  }

  // 초기화 실행
  initChatRoom();
});
