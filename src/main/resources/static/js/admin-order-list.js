(function () {
  "use strict";

  // 관리자 전체 주문(/admin/orders)의 상태 탭. 서버가 준 목록을 화면에서만 나눠 보인다.
  // 각 행의 data-order-status-group(템플릿이 정함: 주문 상태 이름, 취소 · 반려 · 만료는 CLOSED)으로 거른다.
  // 스크립트가 없으면 전체 목록이 그대로 보인다.
  document.addEventListener("DOMContentLoaded", function () {
    const tabsRoot = document.querySelector("[data-admin-order-tabs]");
    if (!tabsRoot) return;

    const tabs = Array.from(tabsRoot.querySelectorAll("[data-order-filter]"));
    const rows = Array.from(document.querySelectorAll("[data-order-status-group]"));
    const filteredEmpty = document.querySelector("[data-order-filtered-empty]");

    function matches(row, filter) {
      return filter === "all" || row.dataset.orderStatusGroup === filter;
    }

    tabs.forEach(function (tab) {
      const countNode = tab.querySelector("[data-order-filter-count]");
      if (!countNode) return;
      const count = rows.filter(function (row) { return matches(row, tab.dataset.orderFilter); }).length;
      countNode.textContent = String(count);
    });

    function apply(filter) {
      let visible = 0;
      rows.forEach(function (row) {
        const show = matches(row, filter);
        row.hidden = !show;
        if (show) visible += 1;
      });
      tabs.forEach(function (tab) {
        const active = tab.dataset.orderFilter === filter;
        tab.classList.toggle("is-active", active);
        tab.setAttribute("aria-pressed", active ? "true" : "false");
      });
      if (filteredEmpty) filteredEmpty.hidden = visible > 0;
    }

    tabs.forEach(function (tab) {
      tab.addEventListener("click", function () {
        apply(tab.dataset.orderFilter);
      });
    });
  });
})();
