/**
 * 관리자용 1:1 동적 채팅 대시보드 REST API + WebSocket STOMP 연동 스크립트
 */
document.addEventListener("DOMContentLoaded", () => {
  let selectedChatRoomId = null;
  let selectedCustomerId = null;
  let preservedDeepLinkedRoomId = null;
  let currentFilter = "all";
  let adminRoomsData = [];
  let pendingAttachment = null;
  let lastFetchedMessageId = 0;

  let stompClient = null;
  let roomSub = null;
  let readSub = null;
  let orderSub = null;

  const adminRoomListContainer = document.getElementById("adminChatRoomList");
  const adminChatMessagesContainer = document.getElementById("adminChatMessages");
  let adminChatForm = document.getElementById("adminChatForm");
  const adminCustomerNote = document.getElementById("adminCustomerNote");
  const saveAdminNoteBtn = document.getElementById("saveAdminNoteBtn");
  const adminSearchInput = document.getElementById("adminChatSearch");

  // 목업 스크립트(admin-mockup.js) 이벤트 간섭 전면 차단을 위한 폼 클로닝
  if (adminChatForm) {
    const freshForm = adminChatForm.cloneNode(true);
    adminChatForm.parentNode.replaceChild(freshForm, adminChatForm);
    adminChatForm = freshForm;
  }

  const adminChatImageInput = document.getElementById("adminChatImageInput");
  const adminImageFileName = document.getElementById("adminImageFileName");

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

  async function openCustomerRoomFromUrl() {
    const urlParams = new URLSearchParams(window.location.search);
    const roomIdParam = urlParams.get("roomId");
    if (roomIdParam && Number.isSafeInteger(Number(roomIdParam)) && Number(roomIdParam) > 0) {
      return false;
    }

    const customerIdParam = urlParams.get("customerId");
    if (!customerIdParam) return false;

    const customerId = Number(customerIdParam);
    if (!Number.isSafeInteger(customerId) || customerId <= 0) {
      console.warn("유효하지 않은 고객 채팅방 이동 요청입니다.");
      return false;
    }

    try {
      const roomUrl = `/api/admin/chat/room?customerId=${encodeURIComponent(customerId)}`;
      let response = await fetch(roomUrl);
      if (response.status === 404) {
        response = await fetch(roomUrl, {
          method: "POST",
          headers: getCsrfHeaders()
        });
      }
      if (!response.ok) {
        throw new Error(`채팅방 생성 또는 조회 실패: ${response.status}`);
      }

      const room = await response.json();
      const roomId = room.id || room.chatRoomId;
      if (!Number.isSafeInteger(roomId) || roomId <= 0) {
        throw new Error("채팅방 ID가 없습니다.");
      }

      return { roomId, customerId };
    } catch (err) {
      console.error("주문 고객 채팅방 열기 실패:", err);
      alert("채팅방을 열지 못했습니다. 잠시 후 다시 시도해주세요.");
      return false;
    }
  }

  async function resolveRoomFromUrl() {
    const urlParams = new URLSearchParams(window.location.search);
    const roomId = Number(urlParams.get("roomId"));
    if (Number.isSafeInteger(roomId) && roomId > 0) {
      try {
        const response = await fetch(`/api/admin/chat/order/rooms/${roomId}`);
        if (!response.ok) {
          throw new Error(`채팅방 조회 실패: ${response.status}`);
        }

        const room = await response.json();
        if (!Number.isSafeInteger(room.chatRoomId) || !Number.isSafeInteger(room.customerId)) {
          throw new Error("채팅방 식별 정보가 올바르지 않습니다.");
        }
        return { roomId: room.chatRoomId, customerId: room.customerId };
      } catch (err) {
        console.error("주문 고객 채팅방 재진입 실패:", err);
        alert("채팅방을 열지 못했습니다. 잠시 후 다시 시도해주세요.");
        return false;
      }
    }

    return openCustomerRoomFromUrl();
  }

  // 미답변 탭 빨간 배지 동적 업데이트 헬퍼
  function updateUnreadTabBadge(count) {
    const badge = document.getElementById("adminUnreadCountBadge");
    if (badge) {
      if (count > 0) {
        badge.textContent = count;
        badge.style.display = "inline-block";
      } else {
        badge.style.display = "none";
      }
    }
  }

  function adjustUnreadTabBadge(delta) {
    const badge = document.getElementById("adminUnreadCountBadge");
    if (badge) {
      let current = parseInt(badge.textContent || "0", 10);
      if (isNaN(current)) current = 0;
      updateUnreadTabBadge(Math.max(0, current + delta));
    }
  }

  let currentRoomPage = 1;
  const ROOM_PAGE_SIZE = 100;
  let hasMoreRooms = false;

  let adminRoomsFetchGen = 0;
  const removedRoomIds = new Set();
  let isMemoDirty = false;

  document.addEventListener("input", (e) => {
    if (e.target && e.target.id === "adminCustomerNote") {
      isMemoDirty = true;
    }
  });

  // 1. 관리자 전체 채팅방 목록 서버 필터 조회
  async function loadAdminRooms(page = 1, append = false, isReconnect = false, skipInitialSelectionRestore = false) {
    const reqFilter = currentFilter;
    const currentGen = ++adminRoomsFetchGen;
    if (!append) {
      currentRoomPage = 1;
      removedRoomIds.clear();
    }
    try {
      let queryUrl = `/api/admin/chat/rooms?page=${page}&size=${ROOM_PAGE_SIZE}`;
      if (reqFilter === "unread") {
        queryUrl += "&status=WAITING_ADMIN";
      } else if (reqFilter === "replied") {
        queryUrl += "&status=WAITING_CUSTOMER";
      } else if (reqFilter === "done") {
        queryUrl += "&status=RESOLVED";
      }

      const response = await fetch(queryUrl);
      if (!response.ok || reqFilter !== currentFilter || currentGen !== adminRoomsFetchGen) {
        if (!response.ok && adminRoomListContainer && !append) {
          adminRoomListContainer.innerHTML = `<div class="text-muted" style="font-size:12px; padding:10px;">채팅방 목록을 불러올 수 없습니다.</div>`;
          clearMainAndSidePanel();
        }
        return;
      }

      let newRooms = await response.json();
      if (reqFilter !== currentFilter || currentGen !== adminRoomsFetchGen) return;

      if (reqFilter !== "all" && removedRoomIds.size > 0) {
        newRooms = newRooms.filter((r) => !removedRoomIds.has(r.chatRoomId || r.id));
      }

      hasMoreRooms = newRooms && newRooms.length >= ROOM_PAGE_SIZE;

      if (append) {
        adminRoomsData = adminRoomsData.concat(newRooms);
      } else {
        if (adminRoomsData && adminRoomsData.length > 0) {
          const roomMap = new Map();
          adminRoomsData.forEach((r) => roomMap.set(r.chatRoomId || r.id, r));
          newRooms.forEach((r) => {
            const existing = roomMap.get(r.chatRoomId || r.id);
            if (existing) {
              const isExistingNewer = (existing.lastMessageId && r.lastMessageId && existing.lastMessageId > r.lastMessageId) ||
                (existing.lastMessageCreatedAt && r.lastMessageCreatedAt && window.CakeShopTime.parse(existing.lastMessageCreatedAt) > window.CakeShopTime.parse(r.lastMessageCreatedAt));

              if (isExistingNewer) {
                r.lastMessageId = existing.lastMessageId;
                r.lastMessageCreatedAt = existing.lastMessageCreatedAt;
                r.lastMessageContent = existing.lastMessageContent;
                if (existing.responseStatus) r.responseStatus = existing.responseStatus;
                if (typeof existing.unreadCount === "number") r.unreadCount = existing.unreadCount;
              }
            }
          });
        }
        adminRoomsData = newRooms;
      }

      renderRoomList();

      // 미답변 방 개수 동적 갱신
      if (currentFilter === "unread") {
        updateUnreadTabBadge(adminRoomsData.length);
      } else if (currentFilter === "all") {
        const unreadCount = adminRoomsData.filter(r => r.responseStatus === "WAITING_ADMIN").length;
        updateUnreadTabBadge(unreadCount);
      }

      if (!skipInitialSelectionRestore) {
        // 초기 진입 시 URL 파라미터 또는 기존 선택된 방 복원 (새로고침 시 방 유지)
        const urlParams = new URLSearchParams(window.location.search);
        const urlRoomId = urlParams.get("roomId") ? parseInt(urlParams.get("roomId"), 10) : null;
        const initialRoomId = selectedChatRoomId || urlRoomId;

        if (initialRoomId) {
          const targetRoom = adminRoomsData.find(r => (r.chatRoomId || r.id) === initialRoomId);
          if (targetRoom) {
            if (preservedDeepLinkedRoomId === initialRoomId) {
              preservedDeepLinkedRoomId = null;
            }
            if (!selectedChatRoomId) {
              const customerId = targetRoom.customerId || targetRoom.memberId;
              selectChatRoom(initialRoomId, customerId);
            }
          } else if (!isReconnect && preservedDeepLinkedRoomId !== initialRoomId) {
            clearMainAndSidePanel();
          }
        } else if (!isReconnect) {
          clearMainAndSidePanel();
        }
      }

      // 웹소켓 연결 및 관리자 대시보드 토픽 구독
      initAdminWebSocket();

    } catch (err) {
      console.error("관리자 방 목록 조회 실패:", err);
      if (!append && !isReconnect && !selectedChatRoomId) {
        clearMainAndSidePanel();
      }
    }
  }

  let isConnectingAdminWebSocket = false;

  // 관리자 웹소켓 STOMP 초기화 및 연결
  function initAdminWebSocket() {
    if (stompClient && stompClient.connected) return;
    if (isConnectingAdminWebSocket) return;
    if (typeof SockJS === "undefined" || typeof Stomp === "undefined") {
      console.warn("SockJS 또는 Stomp 라이브러리가 로드되지 않았습니다.");
      return;
    }

    isConnectingAdminWebSocket = true;
    const socket = new SockJS("/ws");
    stompClient = Stomp.over(socket);
    stompClient.debug = null;

    stompClient.connect({}, async () => {
      isConnectingAdminWebSocket = false;

      // 관리자 대시보드 실시간 토픽 구독 (/topic/admin/rooms)
      stompClient.subscribe("/topic/admin/rooms", (event) => {
        try {
          const msg = JSON.parse(event.body);
          handleAdminRoomUpdateRealtime(msg);
        } catch (e) {
          console.error("관리자 웹소켓 수신 오류:", e);
        }
      });

      // 연결/재연결 완료 시 대시보드 대화방 목록 및 활성 대화 스냅샷 다시 동기화 후 최신 읽음 커서 전송!
      const pageToReload = Math.max(1, currentRoomPage || 1);
      for (let p = 1; p <= pageToReload; p++) {
        await loadAdminRooms(p, p > 1, true);
      }
      currentRoomPage = pageToReload;
      if (selectedChatRoomId) {
        subscribeActiveRoomWebSocket(selectedChatRoomId);
        await loadAdminMessages(selectedChatRoomId, true);
        await loadAdminSidePanel(selectedChatRoomId);
        if (lastFetchedMessageId > 0 && document.visibilityState === "visible") {
          markRead(selectedChatRoomId, lastFetchedMessageId, true);
        }
      }
    }, (err) => {
      isConnectingAdminWebSocket = false;
      console.error("관리자 웹소켓 연결 오류:", err);
      // 지연 재연결 (5초 후 자동 재연결 시도)
      setTimeout(() => {
        initAdminWebSocket();
      }, 5000);
    });
  }

  // 관리자 좌측 목록 실시간 상단 정렬 및 갱신
  function handleAdminRoomUpdateRealtime(msg) {
    if (!msg || !msg.chatRoomId) return;

    const rId = msg.chatRoomId;
    const newStatus = msg.responseStatus ? msg.responseStatus : (msg.senderType === "ADMIN" ? "WAITING_CUSTOMER" : "WAITING_ADMIN");
    let existingRoom = adminRoomsData.find((r) => (r.chatRoomId || r.id) === rId);
    const isCurrentActive = selectedChatRoomId === rId;

    // 현재 탭 필터 조건 검증 (미답변, 답변완료, 상담완료)
    if (currentFilter === "unread" && newStatus !== "WAITING_ADMIN") {
      const wasUnread = existingRoom && existingRoom.responseStatus === "WAITING_ADMIN";
      if (isCurrentActive) {
        // 내가 현재 대화 중인 방이면 목록에서 바로 없애지 않고 '답변 완료' 상태로 유지
        if (existingRoom) {
          existingRoom.responseStatus = newStatus;
          existingRoom.unreadCount = 0;
          if (msg.lastMessageContent || msg.content) {
            existingRoom.lastMessageContent = msg.lastMessageContent || msg.content;
          }
          if (msg.lastMessageCreatedAt || msg.createdAt) {
            existingRoom.lastMessageCreatedAt = msg.lastMessageCreatedAt || msg.createdAt;
          }
          renderRoomList();
        }
        if (wasUnread) {
          adjustUnreadTabBadge(-1);
        }
        return;
      }

      removedRoomIds.add(rId);
      const existingIdx = adminRoomsData.findIndex((r) => (r.chatRoomId || r.id) === rId);
      if (existingIdx !== -1) {
        adminRoomsData.splice(existingIdx, 1);
        renderRoomList();
        if (wasUnread) {
          adjustUnreadTabBadge(-1);
        }
      }
      return;
    }
    if (currentFilter === "replied" && newStatus !== "WAITING_CUSTOMER") {
      if (isCurrentActive) {
        if (existingRoom) {
          existingRoom.responseStatus = newStatus;
          renderRoomList();
        }
        return;
      }
      removedRoomIds.add(rId);
      const existingIdx = adminRoomsData.findIndex((r) => (r.chatRoomId || r.id) === rId);
      if (existingIdx !== -1) {
        adminRoomsData.splice(existingIdx, 1);
        renderRoomList();
      }
      if (newStatus === "WAITING_ADMIN" && (!existingRoom || existingRoom.responseStatus !== "WAITING_ADMIN")) {
        adjustUnreadTabBadge(1);
      }
      return;
    }
    if (currentFilter === "done" && newStatus !== "RESOLVED") {
      removedRoomIds.add(rId);
      const existingIdx = adminRoomsData.findIndex((r) => (r.chatRoomId || r.id) === rId);
      if (existingIdx !== -1) {
        adminRoomsData.splice(existingIdx, 1);
        renderRoomList();
      }
      if (newStatus === "WAITING_ADMIN" && (!existingRoom || existingRoom.responseStatus !== "WAITING_ADMIN")) {
        adjustUnreadTabBadge(1);
      }
      return;
    }

    if (existingRoom) {
      const incomingMsgId = msg.lastMessageId || msg.id;
      const isOlderMessage = incomingMsgId && existingRoom.lastMessageId && incomingMsgId < existingRoom.lastMessageId;
      if (!isOlderMessage) {
        existingRoom.lastMessageContent = msg.lastMessageContent || msg.content || existingRoom.lastMessageContent || (msg.imageUrls && msg.imageUrls.length > 0 ? "(사진)" : "");
        if (msg.lastMessageCreatedAt || msg.createdAt) {
          existingRoom.lastMessageCreatedAt = msg.lastMessageCreatedAt || msg.createdAt;
        }
        existingRoom.responseStatus = newStatus;
        if (incomingMsgId) existingRoom.lastMessageId = incomingMsgId;

        const isVisibleActive = isCurrentActive && document.visibilityState === "visible";
        if (typeof msg.unreadCount === "number") {
          existingRoom.unreadCount = isVisibleActive ? 0 : msg.unreadCount;
        } else if (!isVisibleActive && msg.senderType !== "ADMIN") {
          existingRoom.unreadCount = (existingRoom.unreadCount || 0) + 1;
        } else if (isVisibleActive || msg.senderType === "ADMIN") {
          existingRoom.unreadCount = 0;
        }
      }

      const isNewMessageEvent = Boolean(msg.content || (msg.imageUrls && msg.imageUrls.length > 0) || msg.senderType === "CUSTOMER");
      if (isNewMessageEvent) {
        adminRoomsData = [existingRoom, ...adminRoomsData.filter((r) => (r.chatRoomId || r.id) !== rId)];
      }
    } else {
      const resolvedCustomerId = msg.customerId || (msg.senderType === "CUSTOMER" ? msg.senderId : null);
      const resolvedCustomerName = msg.customerName || (msg.senderType === "CUSTOMER" ? msg.senderName : (resolvedCustomerId ? `고객 #${resolvedCustomerId}` : "고객"));
      const initialUnreadCount = (isCurrentActive || msg.senderType === "ADMIN") ? 0 : (msg.unreadCount != null ? msg.unreadCount : 1);
      const newRoom = {
        chatRoomId: rId,
        customerId: resolvedCustomerId,
        customerName: resolvedCustomerName,
        responseStatus: newStatus,
        lastMessageId: msg.lastMessageId || msg.id,
        lastMessageContent: msg.lastMessageContent || msg.content || (msg.imageUrls && msg.imageUrls.length > 0 ? "(사진)" : ""),
        lastMessageCreatedAt: msg.lastMessageCreatedAt || msg.createdAt,
        unreadCount: initialUnreadCount
      };
      adminRoomsData = [newRoom, ...adminRoomsData];
    }

    renderRoomList();
    const unreadCountTotal = adminRoomsData.filter((r) => r.responseStatus === "WAITING_ADMIN").length;
    updateUnreadTabBadge(unreadCountTotal);
  }

  // 메인 및 사이드 패널 초기화
  function clearMainAndSidePanel() {
    selectedChatRoomId = null;
    selectedCustomerId = null;
    preservedDeepLinkedRoomId = null;

    try {
      const currentUrl = new URL(window.location.href);
      currentUrl.searchParams.delete("roomId");
      window.history.replaceState(null, "", currentUrl.toString());
    } catch (e) {}

    if (adminChatMessagesContainer) {
      adminChatMessagesContainer.innerHTML = `<div class="text-muted" style="text-align:center; padding:40px;">좌측 대화방 목록에서 상담할 대화방을 선택해 주세요.</div>`;
    }

    const infoPanel = document.querySelector(".admin-chat-info-panel");
    if (infoPanel) {
      const existingCards = infoPanel.querySelectorAll(".admin-order-item");
      existingCards.forEach((card) => card.remove());
    }

    const memoEl = document.getElementById("adminCustomerNote");
    if (memoEl) {
      memoEl.value = "";
    }

    const headerTitle = document.querySelector(".admin-chat-main-room .chat-room__header strong");
    if (headerTitle) {
      headerTitle.textContent = "1:1 채팅 상담";
    }
  }

  // 방 목록 렌더링
  function renderRoomList() {
    if (!adminRoomListContainer) return;
    adminRoomListContainer.innerHTML = "";

    const searchKeyword = adminSearchInput ? adminSearchInput.value.trim().toLowerCase() : "";

    const filteredRooms = adminRoomsData.filter((room) => {
      if (searchKeyword) {
        const cName = (room.customerName || "").toLowerCase();
        const oNum = (room.orderNumber || room.orderNo || "").toLowerCase();
        if (!cName.includes(searchKeyword) && !oNum.includes(searchKeyword)) {
          return false;
        }
      }
      return true;
    });

    if (filteredRooms.length === 0) {
      adminRoomListContainer.innerHTML = `<p class="text-muted" style="font-size:12px; padding:10px;">해당하는 채팅방이 없습니다.</p>`;
      if (!searchKeyword && !selectedChatRoomId) {
        clearMainAndSidePanel();
      }
      return;
    }

    filteredRooms.forEach((room) => {
      const rId = room.chatRoomId || room.id;
      const isSelected = rId === selectedChatRoomId;
      const itemDiv = document.createElement("div");
      itemDiv.className = `panel admin-chat-room-item ${isSelected ? "is-selected" : ""}`;
      itemDiv.style.cssText = "cursor:pointer; padding:10px; margin-bottom:8px;";

      const isWaitingAdmin = room.responseStatus === "WAITING_ADMIN";
      const isResolved = room.responseStatus === "RESOLVED";
      const statusBadge = isWaitingAdmin
        ? `<span class="badge badge--danger">답변 대기</span>`
        : (isResolved
            ? `<span class="badge badge--secondary">상담 완료</span>`
            : `<span class="badge badge--info">답변 완료</span>`);

      const unreadBadge = room.unreadCount > 0
        ? `<span class="badge badge--danger">${room.unreadCount}</span>`
        : "";

      const msgTime = room.lastMessageCreatedAt || room.lastMessageTime;

      itemDiv.innerHTML = `
        <div class="cluster cluster--between" style="margin-bottom:4px;">
          <strong>${escapeHtml(room.customerName || `고객 #${room.customerId}`)}</strong>
          ${statusBadge}
        </div>
        <p class="text-muted" style="margin:0; font-size:12px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">
          ${escapeHtml(room.lastMessageContent || "대화 내용 없음")}
        </p>
        <div class="cluster cluster--between" style="margin-top:6px; font-size:11px;">
          <span class="text-muted">${msgTime ? formatTime(msgTime) : ""}</span>
          ${unreadBadge}
        </div>
      `;

      if (selectedChatRoomId === rId && room.customerName) {
        const headerTitle = document.querySelector(".admin-chat-main-room .chat-room__header strong");
        if (headerTitle) {
          headerTitle.textContent = `${room.customerName} 님과의 1:1 상담`;
        }
      }

      itemDiv.addEventListener("click", () => {
        selectChatRoom(rId, room.customerId);
      });

      adminRoomListContainer.appendChild(itemDiv);
    });

    if (hasMoreRooms) {
      const moreBtn = document.createElement("button");
      moreBtn.className = "btn btn--outline btn--block btn--xs";
      moreBtn.style.cssText = "margin-top:8px; margin-bottom:12px;";
      moreBtn.textContent = "+ 이전 문의 더보기";
      moreBtn.addEventListener("click", async () => {
        if (moreBtn.disabled) return;
        moreBtn.disabled = true;
        moreBtn.textContent = "불러오는 중...";
        try {
          currentRoomPage++;
          await loadAdminRooms(currentRoomPage, true);
        } finally {
          moreBtn.disabled = false;
          moreBtn.textContent = "+ 이전 문의 더보기";
        }
      });
      adminRoomListContainer.appendChild(moreBtn);
    }
  }

  // 방 선택 조작
  async function selectChatRoom(roomId, customerId, preserveWhenMissing = false) {
    if (!roomId) return;

    const targetRoom = adminRoomsData.find((r) => (r.chatRoomId || r.id) === roomId);
    preservedDeepLinkedRoomId = preserveWhenMissing && !targetRoom ? roomId : null;

    if (selectedChatRoomId !== roomId) {
      isAdminUploadingAttachment = false;
      isMemoDirty = false;
      const inputEl = document.getElementById("adminChatInput");
      if (inputEl) inputEl.value = "";
      const inputMemo = document.getElementById("adminCustomerNote");
      if (inputMemo) inputMemo.value = "";
      pendingAttachment = null;
      if (adminImageFileName) adminImageFileName.textContent = "선택된 파일 없음";
      if (adminChatImageInput) adminChatImageInput.value = "";
    }

    selectedChatRoomId = roomId;
    selectedCustomerId = customerId;
    lastFetchedMessageId = 0;
    lastSentAdminReadCursorId = 0;

    // URL 파라미터 동기화 (새로고침 시 해당 방 유지)
    try {
      const currentUrl = new URL(window.location.href);
      currentUrl.searchParams.set("roomId", roomId);
      currentUrl.searchParams.delete("customerId");
      window.history.replaceState(null, "", currentUrl.toString());
    } catch (e) {}

    const headerTitle = document.querySelector(".admin-chat-main-room .chat-room__header strong");
    if (headerTitle) {
      const cName = targetRoom ? targetRoom.customerName : `고객 #${customerId}`;
      headerTitle.textContent = `${cName} 님과의 1:1 상담`;
    }

    renderRoomList();

    // 방 교체 시 이전 방 구독 해제 후 새 방 웹소켓 토픽 구독
    subscribeActiveRoomWebSocket(roomId);

    await loadAdminMessages(roomId);
    await loadAdminSidePanel(roomId);
    if (lastFetchedMessageId > 0 && selectedChatRoomId === roomId) {
      await markRead(roomId, lastFetchedMessageId, true);
      if (targetRoom) {
        targetRoom.unreadCount = 0;
        renderRoomList();
      }
    }
  }

  // 활성화된 채팅방 메시지/읽음/연동주문 구독
  function subscribeActiveRoomWebSocket(roomId) {
    if (!stompClient || !stompClient.connected) return;

    try { if (roomSub) roomSub.unsubscribe(); } catch (e) {}
    try { if (readSub) readSub.unsubscribe(); } catch (e) {}
    try { if (orderSub) orderSub.unsubscribe(); } catch (e) {}
    roomSub = null;
    readSub = null;
    orderSub = null;

    // 대화 수신 구독
    roomSub = stompClient.subscribe(`/topic/chat/${roomId}`, (message) => {
      try {
        const msg = JSON.parse(message.body);
        if (String(selectedChatRoomId) === String(roomId)) {
          appendIncomingAdminMessage(msg);
        }
      } catch (e) {
        console.error("관리자 대화 수신 오류:", e);
      }
    });

    // 상대방(고객) 읽음 커서 수신 구독
    readSub = stompClient.subscribe(`/topic/chat/${roomId}/read`, (event) => {
      try {
        const readData = JSON.parse(event.body);
        if (String(selectedChatRoomId) === String(roomId) && readData.readerSide === "CUSTOMER") {
          markAllAdminMessagesRead(readData.lastReadMessageId || readData.lastMessageId);
        }
      } catch (e) {
        console.error("고객 읽음 수신 오류:", e);
      }
    });

    // 실시간 연동 주문 갱신 구독
    orderSub = stompClient.subscribe(`/topic/chat/${roomId}/orders`, (event) => {
      try {
        if (String(selectedChatRoomId) === String(roomId)) {
          loadAdminSidePanel(roomId);
        }
      } catch (e) {
        console.error("관리자 연동 주문 갱신 수신 오류:", e);
      }
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

  // 상대방 메시지 수신 시 타임라인에 추가 헬퍼
  function appendIncomingAdminMessage(msg) {
    if (!adminChatMessagesContainer || !msg) return;

    if (msg.id && document.getElementById(`admin-msg-${msg.id}`)) {
      return;
    }

    const emptyNotice = adminChatMessagesContainer.querySelector(".empty-chat-notice, .text-muted");
    if (emptyNotice && adminChatMessagesContainer.querySelectorAll("[id^='admin-msg-']").length === 0) {
      emptyNotice.remove();
    }

    const isAdminSender = msg.senderType === "ADMIN";
    const msgDiv = document.createElement("div");
    msgDiv.className = `chat-msg ${isAdminSender ? "chat-msg--me" : "chat-msg--other"}`;
    if (msg.id) msgDiv.id = `admin-msg-${msg.id}`;

    const formattedTime = msg.createdAt ? formatTime(msg.createdAt) : "";

    let attachmentsHtml = "";
    if (msg.imageUrls && msg.imageUrls.length > 0) {
      msg.imageUrls.forEach((url) => {
        attachmentsHtml += `<div style="margin-bottom:6px;"><img src="${escapeHtml(url)}" style="max-width:200px; border-radius:8px;" alt="첨부 이미지"/></div>`;
      });
    }

    if (isAdminSender) {
      msgDiv.innerHTML = `
        <div class="chat-msg__body">
          ${attachmentsHtml}
          <div class="chat-msg__content">${escapeHtml(msg.content || "")}</div>
          <div class="chat-msg__meta">
            <span class="chat-msg__read">${(msg.isRead || msg.read) ? "읽음" : "미읽음"}</span>
            <span class="chat-msg__time">${formattedTime}</span>
          </div>
        </div>
      `;
    } else {
      msgDiv.innerHTML = `
        <div class="chat-msg__sender">${escapeHtml(msg.senderName || "고객")}</div>
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

    insertMessageInOrder(adminChatMessagesContainer, msgDiv, msg.id);

    if (msg.productId) {
      appendProductBannerDOM(msg, adminChatMessagesContainer, msgDiv);
    }
    scrollToBottom();

    if (msg.id) {
      lastFetchedMessageId = Math.max(lastFetchedMessageId, msg.id);
    }

    updateReadBadges(adminChatMessagesContainer);

    if (!isAdminSender && msg.id) {
      markRead(selectedChatRoomId, msg.id, true);
    }
  }

  // 탭 복귀, 포커스, 클릭 시 관리자 읽음 커서 즉시 강제 발송
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && selectedChatRoomId && lastFetchedMessageId > 0) {
      markRead(selectedChatRoomId, lastFetchedMessageId, true);
    }
  });
  window.addEventListener("focus", () => {
    if (selectedChatRoomId && lastFetchedMessageId > 0) {
      markRead(selectedChatRoomId, lastFetchedMessageId, true);
    }
  });
  document.addEventListener("click", () => {
    if (selectedChatRoomId && lastFetchedMessageId > 0) {
      markRead(selectedChatRoomId, lastFetchedMessageId, true);
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

  function markAllAdminMessagesRead(lastReadMessageId) {
    if (!adminChatMessagesContainer) return;
    const targetReadId = lastReadMessageId ? parseInt(lastReadMessageId, 10) : null;
    const readMessages = adminChatMessagesContainer.querySelectorAll(".chat-msg--me");
    readMessages.forEach((msgEl) => {
      const badge = msgEl.querySelector(".chat-msg__read");
      if (!badge) return;
      const msgIdAttr = msgEl.getAttribute("id");
      if (msgIdAttr && msgIdAttr.startsWith("admin-msg-")) {
        const msgId = parseInt(msgIdAttr.substring("admin-msg-".length), 10);
        if (!isNaN(msgId) && (targetReadId === null || isNaN(targetReadId) || msgId <= targetReadId)) {
          badge.textContent = "읽음";
        }
      } else {
        badge.textContent = "읽음";
      }
    });
    updateReadBadges(adminChatMessagesContainer);
  }

  // 2. 대화 타임라인 렌더링
  async function loadAdminMessages(roomId, isBackgroundReload = false) {
    if (!adminChatMessagesContainer) return;
    if (!isBackgroundReload) {
      adminChatMessagesContainer.innerHTML = `<div class="text-muted" style="text-align:center; padding:30px;">대화 내용을 불러오는 중입니다...</div>`;
    }
    try {
      const response = await fetch(`/api/chat/messages?chatRoomId=${roomId}&page=1&size=50`);
      if (selectedChatRoomId !== roomId) return;

      if (!response.ok) {
        if (selectedChatRoomId === roomId && !isBackgroundReload) {
          adminChatMessagesContainer.innerHTML = `<div class="text-muted" style="text-align:center; padding:30px;">대화 내역을 불러오는 데 실패했습니다.</div>`;
        }
        return;
      }
      const messages = await response.json();
      if (selectedChatRoomId !== roomId) return;

      renderAdminTimeline(messages);
    } catch (err) {
      console.error("관리자 대화 내역 조회 실패:", err);
      if (selectedChatRoomId === roomId && !isBackgroundReload) {
        adminChatMessagesContainer.innerHTML = `<div class="text-muted" style="text-align:center; padding:30px;">대화 내역을 불러오는 중 오류가 발생했습니다.</div>`;
      }
    }
  }

  function renderAdminTimeline(messages) {
    if (!adminChatMessagesContainer) return;

    // REST 조회 중 STOMP로 먼저 도착했던 신규 메시지 및 상품 배너 DOM 보존
    const existingMsgEls = Array.from(adminChatMessagesContainer.querySelectorAll("[id^='admin-msg-'], [id^='admin-banner-msg-']"));
    const existingReadSet = new Set();
    existingMsgEls.forEach((el) => {
      const readBadge = el.querySelector(".chat-msg__read");
      if (readBadge && readBadge.textContent.trim() === "읽음") {
        const idStr = el.id.replace("admin-banner-msg-", "").replace("admin-msg-", "");
        existingReadSet.add(idStr);
      }
    });

    adminChatMessagesContainer.innerHTML = "";

    if (!messages || messages.length === 0) {
      if (existingMsgEls.length === 0) {
        adminChatMessagesContainer.innerHTML = `<div class="text-muted empty-chat-notice" style="text-align:center; padding:30px;">대화 기록이 없습니다.</div>`;
        lastFetchedMessageId = 0;
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
        appendProductBannerDOM(msg, adminChatMessagesContainer);

        const isAdminSender = msg.senderType === "ADMIN";
        const msgDiv = document.createElement("div");
        msgDiv.className = `chat-msg ${isAdminSender ? "chat-msg--me" : "chat-msg--other"}`;
        if (msg.id) msgDiv.id = `admin-msg-${msg.id}`;

        const formattedTime = msg.createdAt ? formatTime(msg.createdAt) : "";

        let attachmentsHtml = "";
        if (msg.imageUrls && msg.imageUrls.length > 0) {
          msg.imageUrls.forEach((url) => {
            attachmentsHtml += `<div style="margin-bottom:6px;"><img src="${escapeHtml(url)}" style="max-width:200px; border-radius:8px;" alt="첨부 이미지"/></div>`;
          });
        }

        if (isAdminSender) {
          msgDiv.innerHTML = `
            <div class="chat-msg__body">
              ${attachmentsHtml}
              <div class="chat-msg__content">${escapeHtml(msg.content || "")}</div>
              <div class="chat-msg__meta">
                <span class="chat-msg__read">${(msg.isRead || msg.read) ? "읽음" : "미읽음"}</span>
                <span class="chat-msg__time">${formattedTime}</span>
              </div>
            </div>
          `;
        } else {
          msgDiv.innerHTML = `
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

        adminChatMessagesContainer.appendChild(msgDiv);
      });
    }

    // REST 스냅샷에 포함되지 않았던 실시간 메시지 및 배너 DOM 재첨부 (덮어쓰기 방지)
    existingMsgEls.forEach((el) => {
      const idStr = el.id.replace("admin-banner-msg-", "").replace("admin-msg-", "");
      const parsed = parseInt(idStr, 10);
      if (!isNaN(parsed)) maxId = Math.max(maxId, parsed);
      if (!renderedIds.has(idStr)) {
        adminChatMessagesContainer.appendChild(el);
      }
    });

    lastFetchedMessageId = maxId;
    updateReadBadges(adminChatMessagesContainer);
    if (lastFetchedMessageId > 0 && selectedChatRoomId) {
      markRead(selectedChatRoomId, lastFetchedMessageId);
    }
    scrollToBottom();
  }

  // 문의 상품 중앙 시스템 배너 카드 생성 헬퍼
  function appendProductBannerDOM(msg, container, targetMsgEl) {
    if (!msg || !msg.productId || !container) return;
    const bannerId = msg.id ? (container === adminChatMessagesContainer ? `admin-banner-msg-${msg.id}` : `banner-msg-${msg.id}`) : null;
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

  let sidePanelFetchGen = 0;

  // 3. 우측 사이드 패널 렌더링
  async function loadAdminSidePanel(roomId) {
    const currentGen = ++sidePanelFetchGen;
    const inputMemo = document.getElementById("adminCustomerNote");

    try {
      const response = await fetch(`/api/admin/chat/rooms/${roomId}/side-panel`);
      if (!response.ok || selectedChatRoomId !== roomId || currentGen !== sidePanelFetchGen) return;
      const data = await response.json();
      if (selectedChatRoomId !== roomId || currentGen !== sidePanelFetchGen) return;

      if (inputMemo && !isMemoDirty) {
        inputMemo.value = data.note ? (data.note.content || "") : "";
      }

      const infoPanel = document.querySelector(".admin-chat-info-panel");
      if (!infoPanel) return;

      const existingCards = infoPanel.querySelectorAll(".admin-order-item");
      existingCards.forEach((card) => card.remove());

      if (data.orders && data.orders.length > 0) {
        data.orders.forEach((ord) => {
          const cardDiv = document.createElement("div");
          cardDiv.className = "panel section admin-order-item";
          cardDiv.style.cssText = "cursor:pointer; padding:10px; margin-bottom:12px; background:var(--color-background-soft);";

          const oNum = ord.orderNumber || ord.orderNo || `주문 #${ord.orderId}`;
          const pName = ord.productName || ord.productSummary || "주문 제작 케이크";
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

          cardDiv.innerHTML = `
            <div class="cluster cluster--between" style="margin-bottom:6px;">
              <strong>${escapeHtml(oNum)}</strong>
              <span class="badge badge--warning">${escapeHtml(formatOrderStatus(ord.orderStatus))}</span>
            </div>
            <p style="margin:0; font-weight:600;">${escapeHtml(pName)}</p>
            <p class="text-muted" style="font-size:12px; margin:2px 0;">픽업: ${escapeHtml(formatPickupDateTime(pTime))}</p>
            <p style="font-size:13px; font-weight:700; margin-top:4px;">금액: ${amtStr}</p>
            <a class="btn btn--outline btn--block btn--xs" href="/admin/orders/${ord.orderId}" style="margin-top:8px;">주문 상세서 보기</a>
          `;

          if (ord.conversationAnchorMessageId) {
            cardDiv.style.cursor = "pointer";
            cardDiv.addEventListener("click", (e) => {
              if (e.target.closest("a")) return;
              const anchorEl = document.getElementById(`admin-msg-${ord.conversationAnchorMessageId}`);
              if (anchorEl) {
                anchorEl.scrollIntoView({ behavior: "smooth", block: "center" });
                anchorEl.style.transition = "background-color 0.5s";
                anchorEl.style.backgroundColor = "#fff9c4";
                setTimeout(() => { anchorEl.style.backgroundColor = ""; }, 2000);
              }
            });
          }

          infoPanel.appendChild(cardDiv);
        });
      }
    } catch (err) {
      console.error("관리자 사이드 패널 조회 실패:", err);
    }
  }

  // 관리자 Enter 키 단축키 연동 (Shift+Enter는 줄바꿈)
  const adminChatInput = document.getElementById("adminChatInput");
  if (adminChatInput && adminChatForm) {
    adminChatInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        adminChatForm.requestSubmit();
      }
    });
  }

  // 4. 관리자 메시지 전송 (웹소켓 STOMP + REST Fallback)
  if (adminChatForm) {
    adminChatForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      if (!selectedChatRoomId) {
        alert("선택된 대화방이 없습니다.");
        return;
      }
      if (isAdminUploadingAttachment) {
        alert("이미지 업로드가 진행 중입니다. 업로드 완료 후 다시 시도해 주세요.");
        return;
      }

      const inputEl = document.getElementById("adminChatInput");
      const contentText = inputEl ? inputEl.value.trim() : "";
      if (!contentText && !pendingAttachment) {
        alert("답변 메시지 내용 또는 이미지를 첨부해주세요.");
        return;
      }

      if (contentText.length > 2000) {
        alert("메시지는 최대 2,000자까지 입력 가능합니다.");
        return;
      }

      const payload = {
        chatRoomId: selectedChatRoomId,
        content: contentText,
        attachments: pendingAttachment ? [pendingAttachment] : []
      };

      // 웹소켓 연결 시 STOMP 발신
      if (stompClient && stompClient.connected) {
        try {
          stompClient.send("/app/chat/message", {}, JSON.stringify(payload));
          if (lastFetchedMessageId > 0 && selectedChatRoomId) {
            markRead(selectedChatRoomId, lastFetchedMessageId);
          }
          if (inputEl) inputEl.value = "";
          pendingAttachment = null;
          if (adminImageFileName) adminImageFileName.textContent = "선택된 파일 없음";
          if (adminChatImageInput) adminChatImageInput.value = "";
        } catch (stompErr) {
          console.error("관리자 STOMP 메시지 발신 실패:", stompErr);
          alert("답변 전송 중 오류가 발생했습니다.");
        }
        return;
      }

      // REST Fallback
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
          alert("답변 메시지 전송에 실패했습니다.");
          return;
        }

        const sentMsg = await response.json();
        lastFetchedMessageId = sentMsg.id;

        if (inputEl) inputEl.value = "";
        pendingAttachment = null;
        if (adminImageFileName) adminImageFileName.textContent = "선택된 파일 없음";
        if (adminChatImageInput) adminChatImageInput.value = "";

        await loadAdminMessages(selectedChatRoomId);
        await loadAdminRooms(1);
        await markRead(selectedChatRoomId, sentMsg.id);

      } catch (err) {
        console.error("관리자 답장 전송 오류:", err);
        alert("답변 전송 처리 중 오류가 발생했습니다.");
      }
    });
  }

  // 4-2. 관리자 이미지 선택 시 즉시 독립 업로드 실행
  let isAdminUploadingAttachment = false;
  if (adminChatImageInput) {
    adminChatImageInput.addEventListener("change", async () => {
      const file = adminChatImageInput.files[0];
      if (!file) return;

      const currentUploadFile = file;
      isAdminUploadingAttachment = true;
      if (adminImageFileName) adminImageFileName.textContent = `⏳ ${file.name} 업로드 중...`;

      const formData = new FormData();
      formData.append("file", file);

      try {
        const response = await fetch("/api/chat/images", {
          method: "POST",
          headers: getCsrfHeaders(),
          body: formData
        });

        if (!response.ok || adminChatImageInput.files[0] !== currentUploadFile) {
          if (adminChatImageInput.files[0] === currentUploadFile) {
            alert("이미지 업로드에 실패했습니다. (5MB 이하 이미지 파일만 가능합니다)");
            pendingAttachment = null;
            if (adminImageFileName) adminImageFileName.textContent = "선택된 파일 없음";
            if (adminChatImageInput) adminChatImageInput.value = "";
          }
          return;
        }

        const objectKey = await response.text();
        if (adminChatImageInput.files[0] !== currentUploadFile) return;

        pendingAttachment = {
          objectKey: objectKey,
          originalFilename: file.name,
          contentType: file.type || "image/jpeg",
          fileSize: file.size
        };

        if (adminImageFileName) adminImageFileName.textContent = `✔ ${file.name} (첨부 준비 완료)`;

      } catch (err) {
        console.error("관리자 이미지 업로드 오류:", err);
        if (adminChatImageInput.files[0] === currentUploadFile) {
          alert("이미지 업로드 처리 중 에러가 발생했습니다.");
          pendingAttachment = null;
          if (adminImageFileName) adminImageFileName.textContent = "선택된 파일 없음";
          if (adminChatImageInput) adminChatImageInput.value = "";
        }
      } finally {
        if (adminChatImageInput && adminChatImageInput.files[0] === currentUploadFile) {
          isAdminUploadingAttachment = false;
        }
      }
    });
  }

  // 5. 관리자 고객 메모 저장
  if (saveAdminNoteBtn) {
    saveAdminNoteBtn.addEventListener("click", async () => {
      if (!selectedCustomerId) {
        alert("선택된 고객 정보가 없습니다.");
        return;
      }

      const memoEl = document.getElementById("adminCustomerNote");
      const noteText = memoEl ? memoEl.value.trim() : "";

      try {
        const response = await fetch(`/api/admin/chat/customers/${selectedCustomerId}/note`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            ...getCsrfHeaders()
          },
          body: JSON.stringify({ content: noteText })
        });

        if (!response.ok) {
          alert("메모 저장에 실패했습니다.");
          return;
        }

        alert("고객 특이사항 메모가 성공적으로 저장되었습니다!");

      } catch (err) {
        console.error("메모 저장 오류:", err);
        alert("메모 저장 중 오류가 발생했습니다.");
      }
    });
  }

  // 5-2. 관리자 상담 완료(RESOLVED / CLOSED) 처리 버튼 연동
  const completeChatBtn = document.getElementById("completeChatBtn");
  if (completeChatBtn) {
    completeChatBtn.addEventListener("click", async () => {
      if (!selectedChatRoomId) {
        alert("선택된 채팅방이 없습니다.");
        return;
      }

      if (!confirm("해당 문의를 상담 완료 처리하시겠습니까?")) {
        return;
      }

      try {
        const response = await fetch(`/api/admin/chat/rooms/${selectedChatRoomId}/status?status=RESOLVED`, {
          method: "PATCH",
          headers: getCsrfHeaders()
        });

        if (!response.ok) {
          alert("상담 완료 처리에 실패했습니다.");
          return;
        }

        alert("상담이 성공적으로 완료 처리되었습니다.");
        await loadAdminRooms();
      } catch (err) {
        console.error("상담 완료 처리 오류:", err);
        alert("상담 완료 처리 중 오류가 발생했습니다.");
      }
    });
  }

  let lastSentAdminReadCursorId = 0;

  // 6. 관리자 읽음 커서 갱신
  function markRead(roomId, lastMsgId, force = false) {
    if (!roomId || !lastMsgId || lastMsgId <= 0) return;
    if (document.visibilityState !== "visible") return;
    if (!force && lastMsgId <= lastSentAdminReadCursorId) return;

    lastSentAdminReadCursorId = lastMsgId;
    if (stompClient && stompClient.connected) {
      stompClient.send("/app/chat/read", {}, JSON.stringify({
        chatRoomId: roomId,
        lastReadMessageId: lastMsgId
      }));
    } else {
      updateReadCursorAdmin(roomId, lastMsgId);
    }
  }

  async function updateReadCursorAdmin(roomId, lastMsgId) {
    if (!roomId || !lastMsgId) return;
    try {
      await fetch(`/api/chat/read-cursor?chatRoomId=${roomId}&lastReadMessageId=${lastMsgId}`, {
        method: "PATCH",
        headers: getCsrfHeaders()
      });
    } catch (err) {
      console.error("관리자 읽음 커서 갱신 실패:", err);
    }
  }

  // 탭 필터 클릭 이벤트 연동 (HTML 마크업 .admin-chat-filter-tabs [data-admin-filter] 일치)
  const filterTabs = document.querySelectorAll(".admin-chat-filter-tabs [data-admin-filter]");
  filterTabs.forEach((tab) => {
    tab.addEventListener("click", () => {
      filterTabs.forEach((t) => t.classList.remove("is-active"));
      tab.classList.add("is-active");

      const filterVal = tab.getAttribute("data-admin-filter");
      currentFilter = filterVal || "all";
      loadAdminRooms();
    });
  });

  // 검색창 입력 이벤트 연동
  if (adminSearchInput) {
    adminSearchInput.addEventListener("input", () => {
      renderRoomList();
    });
  }

  // 스크롤 맨 아래로 이동
  function scrollToBottom() {
    if (adminChatMessagesContainer) {
      adminChatMessagesContainer.scrollTop = adminChatMessagesContainer.scrollHeight;
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

  // 주문 목록의 딥링크는 채팅 도메인의 권위 있는 방·고객 조합으로 연다.
  resolveRoomFromUrl().then(async (deepLinkedRoom) => {
    await loadAdminRooms(1, false, false, Boolean(deepLinkedRoom));
    if (deepLinkedRoom) {
      await selectChatRoom(deepLinkedRoom.roomId, deepLinkedRoom.customerId, true);
    }
  });
});
