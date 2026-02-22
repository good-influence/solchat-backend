package com.sol.solchat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SolchatApplication {

	public static void main(String[] args) {
		SpringApplication.run(SolchatApplication.class, args);
	}

}
