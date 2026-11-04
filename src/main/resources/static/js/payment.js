(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    const root = document.getElementById("payment-root");
    const button = document.getElementById("request-payment");
    const agreement = document.getElementById("payment-agreement");
    const errorNode = document.getElementById("payment-error");
    const modal = document.getElementById("payment-modal");
    if (!root || !button || !modal) return;

    const choice = document.getElementById("payment-gateway-choice");
    const modalError = document.getElementById("payment-modal-error");
    const tossForm = document.getElementById("choose-toss-form");
    const donWorryForm = document.getElementById("choose-donworry-form");
    const donWorryPanel = document.getElementById("donworry-panel");
    const donWorryFrame = document.getElementById("donworry-frame");
    // 돈워리 결제창이 보내는 메시지는 이 출처에서 온 것만 받는다(결제 준비 응답의 결제창 출처).
    let checkoutOrigin = null;

    function showError(node, message) {
      if (!node) return;
      node.textContent = message;
      node.hidden = !message;
    }

    function optionalCustomerEmail(value) {
      const email = (value || "").trim();
      return email && email.length <= 100 ? email : undefined;
    }

    function optionalCustomerMobilePhone(value) {
      const phone = (value || "").replace(/[^0-9]/g, "");
      return phone.length >= 8 && phone.length <= 15 ? phone : undefined;
    }

    function showChoice() {
      checkoutOrigin = null;
      choice.hidden = false;
      donWorryPanel.hidden = true;
      donWorryFrame.removeAttribute("src");
      showError(modalError, "");
      modal.querySelectorAll(".pg-choice__button").forEach(function (node) {
        node.disabled = node.dataset.unavailable === "true";
      });
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

    modal.querySelectorAll(".pg-choice__button").forEach(function (node) {
      node.dataset.unavailable = String(node.disabled);
    });

    // 결제하기: 동의를 확인한 뒤 결제 수단 선택 모달을 연다.
    button.addEventListener("click", function () {
      if (!agreement || !agreement.checked) {
        agreement.reportValidity();
        return;
      }
      showChoice();
      modal.showModal();
    });

    modal.addEventListener("click", function (event) {
      if (event.target.closest("[data-modal-close]")) modal.close();
    });

    modal.addEventListener("close", function () {
      showChoice();
    });

    if (tossForm) {
      // 토스: 선택을 저장하고 모달을 닫은 뒤 Toss 결제창을 연다.
      tossForm.addEventListener("submit", async function (event) {
        event.preventDefault();
        const tossButton = tossForm.querySelector("button");
        if (!root.dataset.clientKey || typeof TossPayments !== "function") {
          showError(modalError, "결제 모듈을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
          return;
        }

        const amount = Number(root.dataset.paymentAmount);
        if (!Number.isSafeInteger(amount) || amount <= 0) {
          showError(modalError, "결제 금액을 확인할 수 없습니다.");
          return;
        }

        tossButton.disabled = true;
        showError(modalError, "");

        try {
          if (!(await saveSelection(tossForm))) {
            tossButton.disabled = false;
            showError(modalError, "결제 수단을 바꿀 수 없습니다. 이미 진행 중인 결제가 있는지 확인해 주세요.");
            return;
          }
          modal.close();

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
          // 토스 호출.
          await payment.requestPayment(request);
        } catch (error) {
          tossButton.disabled = false;
          showError(errorNode, "결제창을 열지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
      });
    }

    if (donWorryForm) {
      // 돈워리: 선택을 저장하고 돈워리에 결제를 준비한 뒤, 돈워리 결제창을 모달 안 iframe으로 연다.
      donWorryForm.addEventListener("submit", async function (event) {
        event.preventDefault();
        const donWorryButton = donWorryForm.querySelector("button");
        donWorryButton.disabled = true;
        showError(modalError, "");
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
          choice.hidden = true;
          donWorryPanel.hidden = false;
        } catch (error) {
          donWorryButton.disabled = false;
          showError(modalError, "돈워리 결제창을 열지 못했습니다. 이미 진행 중인 결제가 있는지 확인해 주세요.");
        }
      });
    }

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
})();
