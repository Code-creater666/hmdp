package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Test
    void testSavaShop() throws InterruptedException {
            shopService.savaShop2Redis(2L,10L);
    }


}
