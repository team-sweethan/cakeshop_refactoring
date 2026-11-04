package com.cakeshop.domain.product.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.cakeshop.domain.product.dto.view.ProductListView;
import com.cakeshop.domain.product.service.ProductService;
import com.cakeshop.domain.review.service.ReviewProductQueryService;
import com.cakeshop.global.common.paging.PageRequest;
import com.cakeshop.global.common.paging.PageResult;
import com.cakeshop.global.security.SecurityConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
class ProductListScreenRenderingTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private ReviewProductQueryService reviewProductQueryService;

    @BeforeEach
    void setUp() {
        when(productService.getPublicProducts(any(), any()))
                .thenReturn(new PageResult<ProductListView>(
                        List.of(),
                        new PageRequest(1, 8),
                        0
                ));
    }

    @Test
    void productList_withoutType_rendersDefaultTitle()
            throws Exception {
        assertProductListTitle(null, "상품 목록");
    }

    @Test
    void productList_generalType_rendersGeneralCakeTitle()
            throws Exception {
        assertProductListTitle(
                "GENERAL",
                "상품 목록 - 일반 케이크"
        );
    }

    @Test
    void productList_customType_rendersCustomOrderTitle()
            throws Exception {
        assertProductListTitle(
                "CUSTOM",
                "상품 목록 - 주문 제작"
        );
    }

    private void assertProductListTitle(
            String productType,
            String expectedTitle
    ) throws Exception {
        var request = get("/products");

        if (productType != null) {
            request.param("type", productType);
        }

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        expectedTitle + " | 스윗한 케이크"
                )))
                .andExpect(content().string(containsString(
                        "data-page-title=\"" + expectedTitle + "\""
                )))
                .andExpect(content().string(containsString(
                        "<h1>" + expectedTitle + "</h1>"
                )));
    }
}
