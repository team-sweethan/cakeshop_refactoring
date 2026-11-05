(function () {
  "use strict";

  // 관리자 상단 계정 메뉴(<details data-admin-account-menu>). 펼친 채 바깥을 누르거나 Esc를 누르면 닫는다.
  document.addEventListener("DOMContentLoaded", function () {
    const menu = document.querySelector("[data-admin-account-menu]");
    if (!menu) return;

    document.addEventListener("click", function (event) {
      if (menu.open && !menu.contains(event.target)) menu.open = false;
    });

    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && menu.open) {
        menu.open = false;
        const toggle = menu.querySelector("summary");
        if (toggle) toggle.focus();
      }
    });
  });
})();
