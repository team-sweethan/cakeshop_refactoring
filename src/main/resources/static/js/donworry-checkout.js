(function () {
  "use strict";

  // 결제 요청이 끝날 때까지 다시 제출하지 못하게 하고 처리 중 안내를 보여 준다.
  // 같은 창의 재제출은 같은 멱등 키라 결제가 두 번 생기지는 않는다.
  document.addEventListener("DOMContentLoaded", function () {
    const form = document.getElementById("dw-pay-form");
    const button = document.getElementById("dw-pay-button");
    const processing = document.getElementById("dw-processing");
    if (!form || !button || !processing) return;

    form.addEventListener("submit", function () {
      button.disabled = true;
      button.hidden = true;
      processing.hidden = false;
    });
  });
})();
