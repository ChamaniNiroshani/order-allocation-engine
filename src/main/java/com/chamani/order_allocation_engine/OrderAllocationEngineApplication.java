package com.chamani.order_allocation_engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OrderAllocationEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderAllocationEngineApplication.class, args);
	}

}
