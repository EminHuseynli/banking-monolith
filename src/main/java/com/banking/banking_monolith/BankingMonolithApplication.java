package com.banking.banking_monolith;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class BankingMonolithApplication {

	public static void main(String[] args) {
		SpringApplication.run(BankingMonolithApplication.class, args);
	}



}
