(function () {
  "use strict";

  // 결제 화면. 결제 수단은 화면에서 라디오로 고르고(data-payment-gateway), 결제하기를 누르면
  // 고른 결제사를 서버에 저장한 뒤 바로 진행한다.
  //   - 토스페이먼츠: Toss 결제창을 연다.
  //   - 돈워리 안전결제: 돈워리에 결제를 준비하고 돈워리 결제창을 모달 안 iframe으로 연다.
  // 결제 제한 시각까지 남은 시간을 보여 주고, 지나면 결제하기를 막는다.
  document.addEventListener("DOMContentLoaded", function () {
    const root = document.getElementById("payment-root");
    const button = document.getElementById("request-payment");
    const agreement = document.getElementById("payment-agreement");
    const errorNode = document.getElementById("payment-error");
    const modal = document.getElementById("payment-modal");

    initializeTimer(button);
    if (!root || !button || !modal) return;

    const tossForm = document.getElementById("choose-toss-form");
    const donWorryForm = document.getElementById("choose-donworry-form");
    const donWorryFrame = document.getElementById("donworry-frame");
    const buttonLabel = button.textContent.trim();
    // 돈워리 결제창이 보내는 메시지는 이 출처에서 온 것만 받는다(결제 준비 응답의 결제창 출처).
    let checkoutOrigin = null;

    function showError(message) {
      if (!errorNode) return;
      errorNode.textContent = message;
      errorNode.hidden = !message;
    }

    function setBusy(busy) {
      if (button.dataset.expired === "true") return;
      button.disabled = busy;
      button.textContent = busy ? "결제창을 여는 중..." : buttonLabel;
    }

    function selectedGateway() {
      const checked = document.querySelector("[data-payment-gateway]:checked");
      return checked ? checked.value : null;
    }

    function optionalCustomerEmail(value) {
      const email = (value || "").trim();
      return email && email.length <= 100 ? email : undefined;
    }

    function optionalCustomerMobilePhone(value) {
      const phone = (value || "").replace(/[^0-9]/g, "");
      return phone.length >= 8 && phone.length <= 15 ? phone : undefined;
    }

    // 선택한 PG를 서버에 저장한다. 폼의 CSRF 값을 그대로 보낸다.
    async function saveSelection(form) {
      const response = await fetch(form.action, {
        method: "POST",
        body: new FormData(form),
        credentials: "same-origin"
      });
      return response.ok;
    }

    async function payWithToss() {
      if (!root.dataset.clientKey || typeof TossPayments !== "function") {
        showError("결제 모듈을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        return;
      }
      const amount = Number(root.dataset.paymentAmount);
      if (!Number.isSafeInteger(amount) || amount <= 0) {
        showError("결제 금액을 확인할 수 없습니다.");
        return;
      }

      setBusy(true);
      try {
        if (!(await saveSelection(tossForm))) {
          setBusy(false);
          showError("결제 수단을 바꿀 수 없습니다. 이미 진행 중인 결제가 있는지 확인해 주세요.");
          return;
        }
        const tossPayments = TossPayments(root.dataset.clientKey);
        const payment = tossPayments.payment({
          customerKey: TossPayments.ANONYMOUS
        });
        const request = {
          method: "CARD",
          amount: {
            currency: "KRW",
            value: amount
          },
          orderId: root.dataset.gatewayOrderId,
          orderName: root.dataset.orderName,
          customerName: root.dataset.customerName,
          successUrl: new URL(root.dataset.successUrl, location.origin).href,
          failUrl: new URL(root.dataset.failUrl, location.origin).href
        };
        const customerEmail = optionalCustomerEmail(root.dataset.customerEmail);
        const customerMobilePhone = optionalCustomerMobilePhone(root.dataset.customerPhone);
        if (customerEmail) request.customerEmail = customerEmail;
        if (customerMobilePhone) request.customerMobilePhone = customerMobilePhone;
        // 토스 호출. 결제창을 닫으면 여기로 돌아온다.
        await payment.requestPayment(request);
        setBusy(false);
      } catch (error) {
        setBusy(false);
        showError("결제창을 열지 못했습니다. 잠시 후 다시 시도해 주세요.");
      }
    }

    async function payWithDonWorry() {
      setBusy(true);
      try {
        if (!(await saveSelection(donWorryForm))) {
          throw new Error("selection");
        }
        const response = await fetch(modal.dataset.donworrySessionUrl, {
          method: "POST",
          body: new FormData(donWorryForm),
          credentials: "same-origin",
          headers: { Accept: "application/json" }
        });
        if (!response.ok) {
          throw new Error("session");
        }
        const session = await response.json();
        checkoutOrigin = session.checkoutOrigin;
        donWorryFrame.src = session.checkoutUrl;
        modal.showModal();
      } catch (error) {
        setBusy(false);
        showError("돈워리 결제창을 열지 못했습니다. 이미 진행 중인 결제가 있는지 확인해 주세요.");
      }
    }

    // 결제하기: 동의와 결제 수단을 확인한 뒤 고른 결제사로 진행한다.
    button.addEventListener("click", function () {
      showError("");
      if (agreement && !agreement.checked) {
        agreement.reportValidity();
        return;
      }
      const gateway = selectedGateway();
      if (gateway === "donworry" && donWorryForm) {
        payWithDonWorry();
      } else if (gateway === "toss" && tossForm) {
        payWithToss();
      } else {
        showError("결제 수단을 선택해 주세요.");
      }
    });

    modal.addEventListener("click", function (event) {
      if (event.target.closest("[data-modal-close]")) modal.close();
    });

    // 돈워리 결제창을 닫으면 결제를 멈추고 다시 고를 수 있게 한다.
    modal.addEventListener("close", function () {
      checkoutOrigin = null;
      donWorryFrame.removeAttribute("src");
      donWorryFrame.style.height = "";
      setBusy(false);
    });

    // 돈워리 결제창이 결과를 알리면 이 화면이 결과 주소로 이동한다. 이 쇼핑몰 주소만 따른다.
    window.addEventListener("message", function (event) {
      if (!checkoutOrigin || event.origin !== checkoutOrigin) return;
      const data = event.data;
      if (data && data.type === "donworry-checkout-resize" && Number.isFinite(data.height)) {
        // 결제창 내용 높이에 맞춘다. 화면보다 크면 CSS max-height까지만 늘고 iframe 안에서 스크롤한다.
        donWorryFrame.style.height = Math.max(200, Math.ceil(data.height)) + "px";
        return;
      }
      if (!data || data.type !== "donworry-checkout" || typeof data.redirectUrl !== "string") return;
      let target;
      try {
        target = new URL(data.redirectUrl, location.origin);
      } catch (error) {
        return;
      }
      if (target.origin !== location.origin) return;
      location.assign(target.href);
    });
  });

  // 결제 제한 시각(한국 시간)까지 남은 시간을 mm:ss로 줄여 보인다. 1분 아래면 경고색, 지나면 결제하기를 막는다.
  // 서버가 최종으로 만료를 판단하므로 이 표시는 안내용이다.
  function initializeTimer(button) {
    const timer = document.querySelector("[data-payment-timer]");
    const value = timer && timer.querySelector("[data-payment-timer-value]");
    if (!timer || !value || !timer.dataset.expiresAt) return;

    const expiresAt = Date.parse(timer.dataset.expiresAt + "+09:00");
    if (!Number.isFinite(expiresAt)) return;
    let intervalId = null;

    function tick() {
      const remaining = Math.max(0, Math.floor((expiresAt - Date.now()) / 1000));
      const minutes = Math.floor(remaining / 60);
      const seconds = remaining % 60;
      value.textContent = String(minutes).padStart(2, "0") + ":" + String(seconds).padStart(2, "0");
      timer.classList.toggle("is-urgent", remaining > 0 && remaining < 60);
      if (remaining === 0) {
        timer.classList.add("is-expired");
        value.textContent = "시간 초과";
        if (button) {
          button.dataset.expired = "true";
          button.disabled = true;
          button.textContent = "결제 시간이 지났습니다";
        }
        if (intervalId !== null) window.clearInterval(intervalId);
      }
    }

    tick();
    if (!timer.classList.contains("is-expired")) {
      intervalId = window.setInterval(tick, 1000);
    }
  }
})();
