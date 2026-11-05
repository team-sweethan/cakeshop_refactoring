// 성공 알림은 잠시 보여 준 뒤 접어서 없앤다. 오류 알림은 사용자가 읽고 고쳐야 하므로 남긴다.
const SUCCESS_DISMISS_DELAY_MS = 4000;
const DISMISS_TRANSITION_MS = 400;

function removeMessage(message) {
  if (!message.isConnected || message.classList.contains("is-dismissing")) {
    return;
  }

  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    message.remove();
    return;
  }

  // 높이를 숫자로 고정한 다음 0으로 줄여야 아래 내용이 한 번에 튀어 오르지 않는다.
  message.style.height = `${message.offsetHeight}px`;
  message.classList.add("is-dismissing");
  void message.offsetHeight;
  message.style.height = "0px";

  let removed = false;
  const finish = () => {
    if (removed) {
      return;
    }
    removed = true;
    message.remove();
  };
  message.addEventListener("transitionend", (event) => {
    if (event.target === message && event.propertyName === "height") {
      finish();
    }
  });
  // transitionend가 오지 않는 경우(탭 전환 등)에도 지운다.
  window.setTimeout(finish, DISMISS_TRANSITION_MS + 100);
}

function scheduleDismiss(message) {
  let timer = null;
  const start = () => {
    window.clearTimeout(timer);
    timer = window.setTimeout(() => removeMessage(message), SUCCESS_DISMISS_DELAY_MS);
  };

  // 마우스를 올려 읽는 동안은 사라지지 않게 한다.
  message.addEventListener("mouseenter", () => window.clearTimeout(timer));
  message.addEventListener("mouseleave", start);
  start();
}

document.addEventListener("DOMContentLoaded", () => {
  const feedbackMessage = document.querySelector(
    '[data-feedback-message][role="alert"]'
  ) || document.querySelector("[data-feedback-message]");

  if (feedbackMessage) {
    feedbackMessage.focus();
  }

  document
    .querySelectorAll('.alert--success[data-feedback-message]')
    .forEach(scheduleDismiss);
});
