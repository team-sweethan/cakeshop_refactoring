(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    const root = document.getElementById("payment-root");
    const button = document.getElementById("request-payment");
    const agreement = document.getElementById("payment-agreement");
    const errorNode = document.getElementById("payment-error");
    const choice = document.getElementById("payment-gateway-choice");
    const tossForm = document.getElementById("choose-toss-form");
    if (!root || !button) return;

    function showError(message) {
      if (!errorNode) return;
      errorNode.textContent = message;
      errorNode.hidden = !message;
    }

    function optionalCustomerEmail(value) {
      const email = (value || "").trim();
      return email && email.length <= 100 ? email : undefined;
    }

    function optionalCustomerMobilePhone(value) {
      const phone = (value || "").replace(/[^0-9]/g, "");
      return phone.length >= 8 && phone.length <= 15 ? phone : undefined;
    }

    // 결제하기: 동의를 확인한 뒤 결제창(토스 · 돈워리) 선택을 연다.
    button.addEventListener("click", function () {
      if (!agreement || !agreement.checked) {
        agreement.reportValidity();
        return;
      }
      if (!choice) return;
      choice.hidden = false;
      button.setAttribute("aria-expanded", "true");
      const first = choice.querySelector("button:not([disabled])");
      if (first) first.focus();
    });

    if (!tossForm) return;

    // 토스: 서버에 선택을 저장한 뒤 Toss 결제창을 연다. 돈워리는 폼 제출로 돈워리 결제창에 간다.
    tossForm.addEventListener("submit", async function (event) {
      event.preventDefault();
      const tossButton = tossForm.querySelector("button");
      if (!root.dataset.clientKey || typeof TossPayments !== "function") {
        showError("결제 모듈을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        return;
      }

      const amount = Number(root.dataset.paymentAmount);
      if (!Number.isSafeInteger(amount) || amount <= 0) {
        showError("결제 금액을 확인할 수 없습니다.");
        return;
      }

      tossButton.disabled = true;
      showError("");

      try {
        // 폼의 CSRF 값을 그대로 보낸다.
        const response = await fetch(tossForm.action, {
          method: "POST",
          body: new FormData(tossForm),
          credentials: "same-origin"
        });
        if (!response.ok) {
          tossButton.disabled = false;
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
        // 토스 호출.
        await payment.requestPayment(request);
      } catch (error) {
        tossButton.disabled = false;
        showError("결제창을 열지 못했습니다. 잠시 후 다시 시도해 주세요.");
      }
    });
  });
})();
