(() => {
  "use strict";

  // 홈 공지 바: 공지를 세로 띠로 쌓고 일정 간격마다 한 칸씩 위로 밀어 올린다.
  // 서버는 첫 공지만 보이게 내려 주므로 스크립트가 없거나 공지가 한 건이면 그대로 둔다.
  const INTERVAL_MS = 5000;
  const SLIDE_MS = 600;

  const rotation = document.querySelector("[data-notice-rotation]");
  if (!rotation) {
    return;
  }

  const notices = Array.from(rotation.querySelectorAll("[data-notice-item]"));
  if (notices.length < 2) {
    return;
  }

  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

  const track = document.createElement("div");
  track.className = "home-notices__track";
  notices.forEach((notice) => {
    notice.hidden = false;
    track.appendChild(notice);
  });
  rotation.appendChild(track);

  // 보이는 공지 하나만 Tab 이동과 화면 읽기 대상이 되게 한다.
  // 숨은 공지에 포커스가 가면 잘린 영역이 밀려 띠 위치가 어긋난다.
  function syncCurrent() {
    Array.from(track.children).forEach((notice, index) => {
      if (index === 0) {
        notice.removeAttribute("tabindex");
        notice.removeAttribute("aria-hidden");
      } else {
        notice.setAttribute("tabindex", "-1");
        notice.setAttribute("aria-hidden", "true");
      }
    });
  }

  syncCurrent();

  let moving = false;

  // 넘어가는 도중 키보드 포커스가 들어오면 전환을 취소하고 지금 공지를 그대로 둔다.
  // 그대로 마무리하면 포커스한 링크가 맨 뒤로 옮겨지고 숨김 처리되어 Enter 로 열 수 없다.
  function cancelSlide() {
    if (!moving) {
      return;
    }
    track.style.transition = "none";
    track.style.transform = "translateY(0)";
    void track.offsetHeight;
    moving = false;
  }

  rotation.addEventListener("focusin", cancelSlide);

  function finishSlide() {
    if (!moving) {
      return;
    }
    if (rotation.contains(document.activeElement)) {
      cancelSlide();
      return;
    }
    track.style.transition = "none";
    track.appendChild(track.firstElementChild);
    track.style.transform = "translateY(0)";
    // 바뀐 위치를 먼저 적용해야 다음 슬라이드의 transition 이 처음부터 걸린다.
    void track.offsetHeight;
    syncCurrent();
    moving = false;
  }

  window.setInterval(() => {
    if (moving
        || reducedMotion.matches
        || rotation.matches(":hover")
        || rotation.contains(document.activeElement)) {
      return;
    }

    const step = track.firstElementChild.getBoundingClientRect().height;
    moving = true;
    track.style.transition = `transform ${SLIDE_MS}ms ease`;
    track.style.transform = `translateY(-${step}px)`;
    track.addEventListener("transitionend", finishSlide, { once: true });
    // 탭이 백그라운드면 transitionend 가 오지 않을 수 있어 시간이 지나면 직접 마무리한다.
    window.setTimeout(finishSlide, SLIDE_MS + 200);
  }, INTERVAL_MS);
})();
