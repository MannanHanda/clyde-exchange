package com.clydeexchange.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan   // picks up SimProperties
@EnableScheduling   // runs the @Scheduled bots and market-data publisher
public class ClydeExchangeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClydeExchangeApplication.class, args);
    }
}
