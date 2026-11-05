(function () {
  "use strict";

  // 주문 내역 목록(/orders)의 상태 탭. 서버가 준 목록을 화면에서만 나눠 보인다.
  //   all: 전체, active: 진행 중(결제 대기 · 주문 확인 중 · 제작 중 · 픽업 준비),
  //   done: 완료(픽업 완료), closed: 취소 · 반려 · 만료
  // 묶음은 각 카드의 data-order-group(템플릿에서 정함)을 따른다. 스크립트가 없으면 전체 목록이 그대로 보인다.
  document.addEventListener("DOMContentLoaded", function () {
    const list = document.querySelector("[data-order-list]");
    if (!list) return;

    const tabs = Array.from(list.querySelectorAll("[data-order-filter]"));
    const rows = Array.from(list.querySelectorAll("[data-order-group]"));
    const filteredEmpty = list.querySelector("[data-order-filtered-empty]");
    if (tabs.length === 0) return;

    function countOf(filter) {
      return filter === "all"
        ? rows.length
        : rows.filter(function (row) { return row.dataset.orderGroup === filter; }).length;
    }

    tabs.forEach(function (tab) {
      const countNode = tab.querySelector("[data-order-filter-count]");
      if (countNode) countNode.textContent = String(countOf(tab.dataset.orderFilter));
    });

    function apply(filter) {
      let visible = 0;
      rows.forEach(function (row) {
        const show = filter === "all" || row.dataset.orderGroup === filter;
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
