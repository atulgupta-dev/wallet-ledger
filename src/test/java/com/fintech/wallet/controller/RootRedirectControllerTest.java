package com.fintech.wallet.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RootRedirectControllerTest {

    @Test
    void shouldRedirectToSwaggerUi() {
        RootRedirectController controller = new RootRedirectController();
        assertEquals("redirect:/swagger-ui/index.html", controller.redirectToSwagger());
    }
}
