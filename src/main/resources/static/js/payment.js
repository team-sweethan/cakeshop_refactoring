(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    const root = document.getElementById("payment-root");
    const button = document.getElementById("request-payment");
    const agreement = document.getElementById("payment-agreement");
    const errorNode = document.getElementById("payment-error");
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

    button.addEventListener("click", async function () {
      if (!agreement || !agreement.checked) {
        agreement.reportValidity();
        return;
      }
      if (!root.dataset.clientKey || typeof TossPayments !== "function") {
        showError("결제 모듈을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        return;
      }

      const amount = Number(root.dataset.paymentAmount);
      if (!Number.isSafeInteger(amount) || amount <= 0) {
        showError("결제 금액을 확인할 수 없습니다.");
        return;
      }

      button.disabled = true;
      showError("");

      try {
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
        button.disabled = false;
        showError("결제창을 열지 못했습니다. 잠시 후 다시 시도해 주세요.");
      }
    });
  });
})();
