(function () {
  "use strict";

  // 주문 상태 자동 반영. 돈워리가 보류 결제를 승인 · 거절해 서버에서 주문 상태가 바뀌면(웹훅 · 조회 배치)
  // 서버가 주문 ID를 실시간으로 보내고(OrderLiveStatusBroadcaster), 이 스크립트가 지금 화면 주소를 다시 불러와
  // [data-live-region] 영역만 갈아 끼운다. 새로고침 없이 상태 배지 · 목록 · 탭 건수가 바뀐다.
  //
  // 화면 표시: <main data-live-orders="admin|customer" [data-live-order-id="주문 ID"]>
  //   admin    → /topic/admin/orders (관리자만 구독)
  //   customer → /topic/orders/{회원 ID} (본인만 구독)
  //   data-live-order-id가 있으면 그 주문의 알림만 반영한다(상세 화면). 없으면 모든 알림에 반영한다(목록 화면).
  //
  // 입력 중인 영역(포커스가 있거나 사유를 적은 칸)은 바꾸지 않고, 입력을 마치면(포커스가 빠지면) 반영한다.
  // 펼쳐 둔 <details>는 다시 펼친다. 갈아 끼운 뒤 "cakeshop:live-updated" 이벤트를 보내 화면 스크립트가 다시 묶게 한다.
  document.addEventListener("DOMContentLoaded", function () {
    const root = document.querySelector("[data-live-orders]");
    if (!root || typeof SockJS === "undefined" || typeof Stomp === "undefined") return;

    const scope = root.dataset.liveOrders;
    const watchedOrderId = root.dataset.liveOrderId || null;
    const memberId = currentMemberId();
    const topic = scope === "admin"
      ? "/topic/admin/orders"
      : (memberId ? "/topic/orders/" + memberId : null);
    if (!topic) return;

    // deferred: 입력 중이라 갈아 끼우지 못한 영역이 있음 → 입력을 마치면 반영
    // queued: 다시 받는 중에 새 알림이 옴 → 지금 받기가 끝나면 한 번 더 받음
    let deferred = false;
    let queued = false;
    let refreshing = false;

    function currentMemberId() {
      const node = document.querySelector("[data-member-id]");
      const value = node && node.dataset.memberId;
      return value && value !== "null" ? value : null;
    }

    function isBusy(region) {
      const active = document.activeElement;
      if (active && region.contains(active) && active.matches("input, textarea, select")) return true;
      return Array.from(region.querySelectorAll("textarea")).some(function (field) {
        return field.value.trim() !== "";
      });
    }

    async function refresh() {
      if (refreshing) {
        queued = true;
        return;
      }
      refreshing = true;
      queued = false;
      deferred = false;
      try {
        const response = await fetch(location.href, {
          credentials: "same-origin",
          headers: { Accept: "text/html" }
        });
        if (!response.ok) return;
        const next = new DOMParser().parseFromString(await response.text(), "text/html");
        let skipped = false;
        document.querySelectorAll("[data-live-region]").forEach(function (region) {
          if (!region.id) return;
          const replacement = next.getElementById(region.id);
          if (!replacement) return;
          if (isBusy(region)) {
            skipped = true;
            return;
          }
          const openDetails = Array.from(region.querySelectorAll("details[open][id]")).map(function (d) { return d.id; });
          region.replaceWith(replacement);
          openDetails.forEach(function (id) {
            const details = document.getElementById(id);
            if (details) details.open = true;
          });
        });
        if (skipped) deferred = true;
        document.dispatchEvent(new CustomEvent("cakeshop:live-updated"));
      } catch (error) {
        // 다음 알림이나 새로고침 때 맞춰진다.
      } finally {
        refreshing = false;
        // 받는 사이 다른 주문 상태가 바뀌었으면 방금 받은 화면은 그 전 상태일 수 있어 한 번 더 받는다.
        if (queued) refresh();
      }
    }

    // 입력을 마치면 미뤄 둔 반영을 한다.
    document.addEventListener("focusout", function () {
      if (!deferred) return;
      window.setTimeout(function () {
        if (deferred && !refreshing) refresh();
      }, 300);
    });

    function connect(reconnecting) {
      const client = Stomp.over(new SockJS("/ws"));
      client.debug = null;
      client.connect({}, function () {
        client.subscribe(topic, function (message) {
          let orderId = null;
          try {
            orderId = String(JSON.parse(message.body).orderId);
          } catch (error) {
            return;
          }
          if (watchedOrderId && watchedOrderId !== orderId) return;
          refresh();
        });
        // 다시 붙었으면 끊긴 사이 바뀐 것이 있을 수 있어 한 번 맞춘다.
        // 구독을 건 뒤에 받아야 그사이 바뀐 상태도 알림으로 이어진다.
        if (reconnecting) refresh();
      }, function () {
        window.setTimeout(function () {
          connect(true);
        }, 5000);
      });
    }

    connect(false);
  });
})();
